#!/usr/bin/env bash
set -Eeuo pipefail

compose_file="${COMPOSE_FILE:-docker-compose.prod.yml}"
active_compose_file="${ACTIVE_COMPOSE_FILE:-$compose_file}"
image_tar="${IMAGE_TAR:-hampouch-server.tar}"
sha_tag="${DEPLOY_SHA_TAG:?DEPLOY_SHA_TAG가 필요합니다}"
expected_compose_sha="${EXPECTED_COMPOSE_SHA256:?EXPECTED_COMPOSE_SHA256가 필요합니다}"
expected_instance_type="${EXPECTED_INSTANCE_TYPE:-t3.small}"
minimum_available_memory_mb="${MIN_AVAILABLE_MEMORY_MB:-256}"
maximum_container_memory_percent="${MAX_CONTAINER_MEMORY_PERCENT:-90}"
health_attempts="${HEALTH_ATTEMPTS:-24}"
health_interval_seconds="${HEALTH_INTERVAL_SECONDS:-5}"
os_release_file="${OS_RELEASE_FILE:-/etc/os-release}"
meminfo_file="${MEMINFO_FILE:-/proc/meminfo}"

compose=(docker compose -f "$compose_file")
previous_image_id=""
deployment_started=false
deployment_started_epoch=""
staged_compose=false
log_probe_containers=(hampouch-app-log-probe hampouch-mysql-log-probe)

if [ "$compose_file" != "$active_compose_file" ]; then
    staged_compose=true
fi

service_exists() {
    "${compose[@]}" config --services | grep -Fxq "$1"
}

container_is_healthy() {
    [ "$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1")" = "healthy" ]
}

wait_for_health() {
    local container="$1"
    local attempt

    for ((attempt = 1; attempt <= health_attempts; attempt++)); do
        if container_is_healthy "$container"; then
            echo "$container: healthy"
            return 0
        fi
        sleep "$health_interval_seconds"
    done

    echo "$container가 제한 시간 안에 healthy가 되지 않았습니다." >&2
    docker inspect --format='status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" >&2
    return 1
}

print_environment() {
    local metadata_token
    local instance_type
    local ami_id
    local os_name

    metadata_token="$(curl -fsS --max-time 3 -X PUT \
        -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token)"
    instance_type="$(curl -fsS --max-time 3 \
        -H "X-aws-ec2-metadata-token: $metadata_token" \
        http://169.254.169.254/latest/meta-data/instance-type)"
    ami_id="$(curl -fsS --max-time 3 \
        -H "X-aws-ec2-metadata-token: $metadata_token" \
        http://169.254.169.254/latest/meta-data/ami-id)"
    os_name="$(sed -n 's/^PRETTY_NAME=//p' "$os_release_file" | tr -d '"')"

    echo "instance_type=$instance_type"
    echo "ami_id=$ami_id"
    echo "os=$os_name"
    echo "architecture=$(uname -m)"
    docker --version
    docker compose version

    if [ "$instance_type" != "$expected_instance_type" ]; then
        echo "배포 대상 인스턴스가 $expected_instance_type가 아닙니다: $instance_type" >&2
        return 1
    fi
}

verify_compose() {
    local actual_compose_sha

    actual_compose_sha="$(sha256sum "$compose_file" | awk '{print $1}')"
    if [ "$actual_compose_sha" != "$expected_compose_sha" ]; then
        echo "운영 Compose 체크섬이 배포 산출물과 다릅니다." >&2
        return 1
    fi
    "${compose[@]}" config --quiet
    echo "compose_sha256=$actual_compose_sha"
}

verify_app_and_database() {
    local app_image_id

    app_image_id="$(docker inspect --format='{{.Image}}' hampouch-server)"
    if [ "$app_image_id" != "$1" ]; then
        echo "실행 중인 앱 이미지가 배포 SHA 이미지와 다릅니다." >&2
        return 1
    fi

    if ! docker exec hampouch-server curl -fsS http://localhost:8081/actuator/health/readiness 2>/dev/null \
        | grep -q '"status":"UP"'; then
        docker exec hampouch-server curl -fsS http://localhost:8080/actuator/health \
            | grep -q '"status":"UP"'
    fi

    docker exec hampouch-mysql sh -ec \
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" "$MYSQL_DATABASE" --skip-column-names --execute="SELECT 1"' \
        | grep -qx '1'
    echo "앱 health와 MySQL 읽기 전용 요청을 확인했습니다."
}

cleanup_log_probes() {
    docker rm -f "${log_probe_containers[@]}" >/dev/null 2>&1 || true
}

verify_datadog_delivery() {
    local app_image_id="$1"
    local marker="hampouch_deploy_verification sha:${sha_tag}"
    local mysql_image_id
    local verification_exit_code=0

    command -v python3 >/dev/null
    mysql_image_id="$(docker inspect --format='{{.Image}}' hampouch-mysql)"
    cleanup_log_probes
    docker run --detach --name "${log_probe_containers[0]}" \
        --label com.datadoghq.tags.env=prod \
        --label com.datadoghq.tags.service=hampouch-server \
        --label 'com.datadoghq.ad.logs=[{"source":"java","service":"hampouch-server"}]' \
        --env HAMPOUCH_DEPLOY_MARKER="$marker" \
        --entrypoint /bin/sh \
        "$app_image_id" -ec \
        'while :; do printf "%s\n" "$HAMPOUCH_DEPLOY_MARKER"; sleep 5; done' >/dev/null
    docker run --detach --name "${log_probe_containers[1]}" \
        --label com.datadoghq.tags.env=prod \
        --label com.datadoghq.tags.service=hampouch-mysql \
        --label 'com.datadoghq.ad.logs=[{"source":"mysql","service":"hampouch-mysql"}]' \
        --env HAMPOUCH_DEPLOY_MARKER="$marker" \
        --entrypoint /bin/sh \
        "$mysql_image_id" -ec \
        'while :; do printf "%s\n" "$HAMPOUCH_DEPLOY_MARKER"; sleep 5; done' >/dev/null
    echo "Datadog 로그 도착 검증용 임시 앱·MySQL 로그 표식을 기록했습니다."

    python3 scripts/deployment/datadog_verification.py \
        --env-file .env \
        deployment \
        --from-epoch "$deployment_started_epoch" \
        --sha "$sha_tag" || verification_exit_code="$?"
    cleanup_log_probes
    return "$verification_exit_code"
}

verify_resources() {
    local available_memory_mb
    local stats
    local container
    local memory_percent
    local numeric_percent
    local containers=(hampouch-server hampouch-mysql)

    if service_exists datadog; then
        containers+=(hampouch-datadog)
    fi

    available_memory_mb="$(awk '/MemAvailable:/ {print int($2 / 1024)}' "$meminfo_file")"
    echo "host_available_memory_mb=$available_memory_mb"
    if [ "$available_memory_mb" -lt "$minimum_available_memory_mb" ]; then
        echo "호스트 가용 메모리가 ${minimum_available_memory_mb}MB보다 적습니다." >&2
        return 1
    fi

    stats="$(docker stats --no-stream --format '{{.Name}}|{{.MemPerc}}' "${containers[@]}")"
    while IFS='|' read -r container memory_percent; do
        numeric_percent="${memory_percent%%%}"
        echo "$container memory_percent=$numeric_percent"
        if ! awk -v value="$numeric_percent" -v limit="$maximum_container_memory_percent" \
            'BEGIN { exit(value <= limit ? 0 : 1) }'; then
            echo "$container 메모리 사용률이 ${maximum_container_memory_percent}%를 넘었습니다." >&2
            return 1
        fi
    done <<<"$stats"
}

print_diagnostics() {
    "${compose[@]}" ps -a || true
    for container in hampouch-server hampouch-mysql hampouch-datadog; do
        if docker inspect "$container" >/dev/null 2>&1; then
            docker logs --tail 150 "$container" || true
        fi
    done
    free -h || true
    docker stats --no-stream || true
}

rollback() {
    local rollback_image_id
    local -a rollback_compose=("${compose[@]}")

    if [ -z "$previous_image_id" ]; then
        echo "복구할 이전 앱 이미지가 없습니다." >&2
        return 1
    fi
    if [ "$staged_compose" = true ] && [ -f "$active_compose_file" ]; then
        rollback_compose=(docker compose -f "$active_compose_file")
    fi

    docker image tag "$previous_image_id" hampouch-server:latest || return 1
    "${rollback_compose[@]}" up -d --no-deps --force-recreate app || return 1
    wait_for_health hampouch-server || return 1
    rollback_image_id="$(docker inspect --format='{{.Image}}' hampouch-server)" || return 1
    if [ "$rollback_image_id" != "$previous_image_id" ]; then
        echo "롤백 컨테이너가 이전 이미지로 생성되지 않았습니다." >&2
        return 1
    fi
    echo "이전 이미지로 앱 컨테이너를 복구하고 health를 확인했습니다."
}

discard_staged_compose() {
    if [ "$staged_compose" = true ]; then
        rm -f -- "$compose_file"
    fi
}

promote_staged_compose() {
    if [ "$staged_compose" = true ]; then
        mv -f -- "$compose_file" "$active_compose_file"
    fi
}

on_error() {
    local exit_code="$?"
    local rollback_exit_code=0

    trap - ERR
    set +e
    cleanup_log_probes
    echo "배포 검증이 실패했습니다." >&2
    print_diagnostics
    if [ "$deployment_started" = true ]; then
        rollback
        rollback_exit_code="$?"
        if [ "$rollback_exit_code" -ne 0 ]; then
            echo "자동 롤백도 실패했습니다." >&2
        else
            discard_staged_compose
        fi
    fi
    exit "$exit_code"
}

cleanup_old_images() {
    local tag

    while IFS= read -r tag; do
        [ -n "$tag" ] || continue
        docker image rm "hampouch-server:$tag" || true
    done < <(
        docker image ls hampouch-server --format '{{.Tag}}' \
            | grep -E '^[0-9a-f]{8}$' \
            | while read -r tag; do
                printf '%s %s\n' "$(docker image inspect "hampouch-server:$tag" --format='{{.Created}}')" "$tag"
            done \
            | sort -r \
            | tail -n +4 \
            | awk '{print $2}'
    )
    docker image prune -f
}

trap on_error ERR

for command_name in curl docker free sha256sum; do
    command -v "$command_name" >/dev/null
done

print_environment
verify_compose

previous_image_id="$(docker inspect --format='{{.Image}}' hampouch-server 2>/dev/null || true)"
if [ -z "$previous_image_id" ]; then
    previous_image_id="$(docker image inspect hampouch-server:latest --format='{{.Id}}' 2>/dev/null || true)"
fi

docker load -i "$image_tar"
rm -f "$image_tar"

expected_image_id="$(docker image inspect "hampouch-server:$sha_tag" --format='{{.Id}}')"
latest_image_id="$(docker image inspect hampouch-server:latest --format='{{.Id}}')"
if [ "$expected_image_id" != "$latest_image_id" ]; then
    echo "latest 태그가 이번 배포 SHA 이미지를 가리키지 않습니다." >&2
    exit 1
fi

if service_exists datadog; then
    "${compose[@]}" pull datadog
fi

deployment_started=true
deployment_started_epoch="$(date +%s)"
"${compose[@]}" up -d
wait_for_health hampouch-mysql
wait_for_health hampouch-server

if service_exists datadog; then
    wait_for_health hampouch-datadog
    if [ -f scripts/observability/verify-datadog.sh ]; then
        bash scripts/observability/verify-datadog.sh
    else
        docker exec hampouch-datadog agent health
    fi
fi

verify_app_and_database "$expected_image_id"
if service_exists datadog; then
    verify_datadog_delivery "$expected_image_id"
fi
verify_resources

promote_staged_compose
deployment_started=false
trap - ERR
cleanup_old_images
echo "배포 SHA ${sha_tag}의 운영 환경 검증을 통과했습니다."
