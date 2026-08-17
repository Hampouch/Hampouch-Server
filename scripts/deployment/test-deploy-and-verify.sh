#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deployment_script="$script_dir/deploy-and-verify.sh"
test_temp_parent="${TEST_TMPDIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}}"
test_root="$(mktemp -d "$test_temp_parent/hampouch-deploy-test.XXXXXX")"

cleanup() {
    if [ -n "${test_root:-}" ] && [ -d "$test_root" ]; then
        rm -rf -- "$test_root"
    fi
}

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

read_state() {
    tr -d '\n' <"$FAKE_STATE_DIR/$1"
}

write_state() {
    printf '%s\n' "$2" >"$FAKE_STATE_DIR/$1"
}

docker() {
    local command="${1:-}"
    shift || true

    case "$command" in
        --version)
            echo "Docker version test"
            ;;
        compose)
            if [ "${1:-}" = "version" ]; then
                echo "Docker Compose version test"
                return 0
            fi
            [ "${1:-}" = "-f" ] || fail "compose 파일 인자가 없습니다"
            local selected_compose_file="${2:-}"
            shift 2
            case "${1:-}" in
                config)
                    if [ "${2:-}" = "--services" ]; then
                        printf 'db\napp\ndatadog\n'
                    fi
                    ;;
                pull)
                    printf 'compose-pull:%s:%s\n' "$selected_compose_file" "${2:-}" \
                        >>"$FAKE_STATE_DIR/events"
                    ;;
                up)
                    write_state current_image "$(read_state latest_image)"
                    printf 'compose-up:%s\n' "$selected_compose_file" >>"$FAKE_STATE_DIR/events"
                    ;;
                ps)
                    echo "fake compose ps"
                    ;;
                *)
                    fail "지원하지 않는 docker compose 호출: $*"
                    ;;
            esac
            ;;
        inspect)
            if [[ "${1:-}" != --format=* ]]; then
                case "${1:-}" in
                    hampouch-server | hampouch-mysql | hampouch-datadog) return 0 ;;
                    *) return 1 ;;
                esac
            fi
            case "${1:-}" in
                *".Image"*)
                    if [ "${2:-}" = "hampouch-mysql" ]; then
                        echo "mysql-image"
                    else
                        read_state current_image
                    fi
                    ;;
                *)
                    printf 'health:%s\n' "${2:-}" >>"$FAKE_STATE_DIR/events"
                    echo "healthy"
                    ;;
            esac
            ;;
        image)
            local image_command="${1:-}"
            shift || true
            case "$image_command" in
                inspect)
                    case "${1:-}" in
                        hampouch-server:latest) read_state latest_image ;;
                        hampouch-server:*) read_state new_image ;;
                        *) fail "지원하지 않는 이미지 조회: ${1:-}" ;;
                    esac
                    ;;
                tag)
                    write_state latest_image "${1:-}"
                    ;;
                ls | rm | prune)
                    return 0
                    ;;
                *)
                    fail "지원하지 않는 docker image 호출: $image_command"
                    ;;
            esac
            ;;
        load)
            write_state latest_image "$(read_state new_image)"
            ;;
        exec)
            if [ "${1:-}" = "-e" ]; then
                fail "실행 중인 컨테이너의 /proc에 로그 표식을 쓰면 안 됩니다"
            fi
            local container="${1:-}"
            shift || true
            case "$container" in
                hampouch-server)
                    if [ "${FAKE_APP_CHECK_FAIL:-0}" = "1" ]; then
                        return 1
                    fi
                    echo '{"status":"UP"}'
                    ;;
                hampouch-mysql)
                    echo "1"
                    ;;
                hampouch-datadog)
                    echo "Datadog Agent is running"
                    ;;
                *)
                    fail "지원하지 않는 docker exec 대상: $container"
                    ;;
            esac
            ;;
        run)
            local probe_name=""
            local probe_service=""
            local probe_marker=""
            while [ "$#" -gt 0 ]; do
                case "$1" in
                    --detach)
                        shift
                        ;;
                    --name)
                        probe_name="${2:-}"
                        shift 2
                        ;;
                    --label)
                        case "${2:-}" in
                            com.datadoghq.tags.service=*)
                                probe_service="${2#com.datadoghq.tags.service=}"
                                ;;
                        esac
                        shift 2
                        ;;
                    --env)
                        probe_marker="${2#HAMPOUCH_DEPLOY_MARKER=}"
                        shift 2
                        ;;
                    --entrypoint)
                        shift 2
                        ;;
                    *)
                        break
                        ;;
                esac
            done
            [ -n "$probe_name" ] || fail "로그 검증 컨테이너 이름이 없습니다"
            [ -n "$probe_service" ] || fail "로그 검증 서비스 라벨이 없습니다"
            [ -n "$probe_marker" ] || fail "로그 검증 표식이 없습니다"
            printf 'probe:%s:%s:%s\n' "$probe_name" "$probe_service" "$probe_marker" \
                >>"$FAKE_STATE_DIR/events"
            ;;
        rm)
            printf 'probe-cleanup:%s\n' "$*" >>"$FAKE_STATE_DIR/events"
            return 0
            ;;
        stats)
            if [[ " $* " == *" --format "* ]]; then
                printf 'hampouch-server|10.00%%\nhampouch-mysql|20.00%%\nhampouch-datadog|15.00%%\n'
            else
                echo "fake docker stats"
            fi
            ;;
        logs)
            return 0
            ;;
        *)
            fail "지원하지 않는 docker 호출: $command $*"
            ;;
    esac
}

curl() {
    local url="${!#}"

    case "$url" in
        */api/token) echo "test-token" ;;
        */instance-type) echo "t3.small" ;;
        */ami-id) echo "ami-test" ;;
        *) return 22 ;;
    esac
}

free() {
    echo "fake free"
}

sha256sum() {
    printf 'compose-hash  %s\n' "${1:-}"
}

sleep() {
    return 0
}

timeout() {
    printf 'datadog-process-timeout:%s\n' "$*" >>"$FAKE_STATE_DIR/events"
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --signal=* | --kill-after=*)
                shift
                ;;
            *s)
                shift
                break
                ;;
            *)
                break
                ;;
        esac
    done
    if [ "${FAKE_DATADOG_SIGNAL:-0}" = "1" ]; then
        while :; do
            :
        done
    fi
    if [ "${FAKE_DATADOG_TIMEOUT:-0}" = "1" ]; then
        return 124
    fi
    "$@"
}

python3() {
    [ "${1:-}" = "-u" ] || fail "Datadog 검증 Python이 unbuffered 모드가 아닙니다: $*"
    shift
    [ "${1:-}" = "scripts/deployment/datadog_verification.py" ] \
        || fail "지원하지 않는 Python 실행: $*"
    printf 'datadog-verification\n' >>"$FAKE_STATE_DIR/events"
    if [ "${FAKE_DATADOG_LOG_PENDING:-0}" = "1" ]; then
        return 3
    fi
    [ "${FAKE_DATADOG_DELIVERY_FAIL:-0}" != "1" ]
}

export -f curl docker fail free python3 read_state sha256sum sleep timeout write_state
trap cleanup EXIT

run_case() {
    local case_name="$1"
    local app_check_fail="$2"
    local datadog_delivery_fail="$3"
    local datadog_timeout="$4"
    local datadog_signal="$5"
    local datadog_log_pending="${6:-0}"
    local case_dir="$test_root/$case_name"
    local state_dir="$case_dir/state"
    local log_file="$case_dir/run.log"
    local expected_failure=0
    local deployment_status=0
    local deployment_pid=""
    local attempt

    if [ "$app_check_fail" = "1" ] \
        || [ "$datadog_delivery_fail" = "1" ] \
        || [ "$datadog_timeout" = "1" ] \
        || [ "$datadog_signal" = "1" ]; then
        expected_failure=1
    fi

    mkdir -p "$state_dir" "$case_dir/scripts/deployment" "$case_dir/scripts/observability"
    printf 'active compose\n' >"$case_dir/docker-compose.prod.yml"
    printf 'services:\n  app: {}\n  db: {}\n  datadog: {}\n' >"$case_dir/docker-compose.prod.next.yml"
    printf 'image archive\n' >"$case_dir/hampouch-server.tar"
    printf 'PRETTY_NAME="Test Linux"\n' >"$case_dir/os-release"
    printf 'MemAvailable: 1048576 kB\n' >"$case_dir/meminfo"
    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'set -euo pipefail' \
        "printf 'verify-datadog\\n' >>\"\$FAKE_STATE_DIR/events\"" \
        >"$case_dir/scripts/observability/verify-datadog.sh"
    printf 'fake verifier\n' >"$case_dir/scripts/deployment/datadog_verification.py"
    printf 'old-image\n' >"$state_dir/current_image"
    printf 'old-image\n' >"$state_dir/latest_image"
    printf 'new-image\n' >"$state_dir/new_image"
    : >"$state_dir/events"

    export FAKE_STATE_DIR="$state_dir"
    export FAKE_APP_CHECK_FAIL="$app_check_fail"
    export FAKE_DATADOG_DELIVERY_FAIL="$datadog_delivery_fail"
    export FAKE_DATADOG_TIMEOUT="$datadog_timeout"
    export FAKE_DATADOG_SIGNAL="$datadog_signal"
    export FAKE_DATADOG_LOG_PENDING="$datadog_log_pending"

    if [ "$datadog_signal" = "1" ]; then
        (
            cd "$case_dir"
            exec env \
                DEPLOY_SHA_TAG=abcdef12 \
                EXPECTED_COMPOSE_SHA256=compose-hash \
                COMPOSE_FILE=docker-compose.prod.next.yml \
                ACTIVE_COMPOSE_FILE=docker-compose.prod.yml \
                HEALTH_ATTEMPTS=1 \
                HEALTH_INTERVAL_SECONDS=0 \
                OS_RELEASE_FILE="$case_dir/os-release" \
                MEMINFO_FILE="$case_dir/meminfo" \
                bash "$deployment_script"
        ) >"$log_file" 2>&1 &
        deployment_pid="$!"
        for ((attempt = 1; attempt <= 100; attempt++)); do
            if grep -q '^datadog-process-timeout:' "$state_dir/events"; then
                break
            fi
            /bin/sleep 0.01
        done
        grep -q '^datadog-process-timeout:' "$state_dir/events" \
            || fail "취소 신호를 보낼 Datadog 검증 프로세스가 시작되지 않았습니다"
        kill -TERM "$deployment_pid"
        if wait "$deployment_pid"; then
            deployment_status=0
        else
            deployment_status="$?"
        fi
    elif (
        cd "$case_dir"
        DEPLOY_SHA_TAG=abcdef12 \
            EXPECTED_COMPOSE_SHA256=compose-hash \
            COMPOSE_FILE=docker-compose.prod.next.yml \
            ACTIVE_COMPOSE_FILE=docker-compose.prod.yml \
            HEALTH_ATTEMPTS=1 \
            HEALTH_INTERVAL_SECONDS=0 \
            OS_RELEASE_FILE="$case_dir/os-release" \
            MEMINFO_FILE="$case_dir/meminfo" \
            bash "$deployment_script"
    ) >"$log_file" 2>&1; then
        deployment_status=0
    else
        deployment_status="$?"
    fi

    if [ "$deployment_status" = "0" ]; then
        [ "$expected_failure" = "0" ] || fail "실패 시나리오가 성공했습니다"
    else
        [ "$expected_failure" = "1" ] || {
            sed -n '1,240p' "$log_file" >&2
            fail "성공 시나리오가 실패했습니다"
        }
    fi
    if [ "$datadog_timeout" = "1" ]; then
        [ "$deployment_status" = "124" ] || fail "timeout 종료 코드가 124가 아닙니다: $deployment_status"
    fi
    if [ "$datadog_signal" = "1" ]; then
        [ "$deployment_status" = "143" ] || fail "TERM 종료 코드가 143이 아닙니다: $deployment_status"
    fi

    grep -qx 'health:hampouch-datadog' "$state_dir/events" \
        || fail "Datadog health 대기가 실행되지 않았습니다"
    grep -qx 'verify-datadog' "$state_dir/events" \
        || fail "Datadog Agent 검증이 실행되지 않았습니다"
    [ "$(grep -c '^compose-pull:docker-compose.prod.next.yml:datadog$' "$state_dir/events")" = "1" ] \
        || fail "배포 전에 Datadog Agent 이미지를 한 번 갱신하지 않았습니다"

    if [ "$app_check_fail" = "0" ]; then
        grep -qx 'probe:hampouch-app-log-probe:hampouch-server:hampouch_deploy_verification sha:abcdef12' "$state_dir/events" \
            || fail "앱 로그 배포 표식이 기록되지 않았습니다"
        grep -qx 'probe:hampouch-mysql-log-probe:hampouch-mysql:hampouch_deploy_verification sha:abcdef12' "$state_dir/events" \
            || fail "MySQL 로그 배포 표식이 기록되지 않았습니다"
        grep -q '^datadog-process-timeout:--signal=TERM --kill-after=5s 310s python3 -u scripts/deployment/datadog_verification.py ' "$state_dir/events" \
            || fail "Datadog 검증 프로세스의 절대 제한이 적용되지 않았습니다"
        [ "$(grep -c '^probe-cleanup:' "$state_dir/events")" -ge 2 ] \
            || fail "Datadog 로그 프로브가 검증 종료 뒤 정리되지 않았습니다"
        if [ "$datadog_timeout" = "0" ] && [ "$datadog_signal" = "0" ]; then
            grep -qx 'datadog-verification' "$state_dir/events" \
                || fail "Datadog 데이터 도착 검증이 실행되지 않았습니다"
        elif grep -qx 'datadog-verification' "$state_dir/events"; then
            fail "timeout 또는 취소 신호 뒤 Datadog 검증기가 실행됐습니다"
        fi
        if [ "$datadog_log_pending" = "1" ]; then
            [ "$(grep -c '^datadog-verification$' "$state_dir/events")" = "2" ] \
                || fail "로그 도착 미확인 시 검증이 1회 재시도되지 않았습니다"
            grep -q '검증을 1회 재시도합니다' "$log_file" \
                || fail "로그 도착 재시도 안내가 배포 로그에 없습니다"
            grep -q '경고: Datadog 로그 도착을 끝내 확인하지 못했지만' "$log_file" \
                || fail "로그 도착 경고 강등 안내가 배포 로그에 없습니다"
        elif [ "$datadog_delivery_fail" = "1" ]; then
            [ "$(grep -c '^datadog-verification$' "$state_dir/events")" = "1" ] \
                || fail "치명 실패에서 Datadog 검증이 재시도됐습니다"
        fi
    fi

    if [ "$expected_failure" = "0" ]; then
        [ "$(read_state current_image)" = "new-image" ] || fail "새 이미지가 실행되지 않았습니다"
        [ "$(grep -c '^compose-up:docker-compose.prod.next.yml$' "$state_dir/events")" = "1" ] \
            || fail "성공 배포의 compose 실행 횟수가 다릅니다"
        [ ! -f "$case_dir/docker-compose.prod.next.yml" ] || fail "검증을 통과한 임시 Compose가 남았습니다"
        grep -q '^services:' "$case_dir/docker-compose.prod.yml" \
            || fail "검증을 통과한 Compose가 활성 파일로 승격되지 않았습니다"
        grep -q '운영 환경 검증을 통과했습니다' "$log_file" || fail "성공 로그가 없습니다"
    else
        [ "$(read_state current_image)" = "old-image" ] || fail "이전 이미지로 롤백되지 않았습니다"
        [ "$(read_state latest_image)" = "old-image" ] || fail "latest가 이전 이미지로 복구되지 않았습니다"
        grep -qx 'compose-up:docker-compose.prod.next.yml' "$state_dir/events" \
            || fail "새 Compose로 배포하지 않았습니다"
        grep -qx 'compose-up:docker-compose.prod.yml' "$state_dir/events" \
            || fail "기존 Compose로 롤백하지 않았습니다"
        [ ! -f "$case_dir/docker-compose.prod.next.yml" ] || fail "롤백 뒤 임시 Compose가 남았습니다"
        grep -qx 'active compose' "$case_dir/docker-compose.prod.yml" \
            || fail "롤백 뒤 활성 Compose가 바뀌었습니다"
        grep -q '이전 이미지로 앱 컨테이너를 복구하고 health를 확인했습니다' "$log_file" \
            || fail "롤백 성공 로그가 없습니다"
        if [ "$datadog_signal" = "1" ]; then
            grep -q 'TERM 신호로 중단됐습니다' "$log_file" \
                || fail "취소 신호 원인이 배포 로그에 남지 않았습니다"
        fi
    fi
}

run_case success 0 0 0 0
run_case app-check-rollback 1 0 0 0
run_case datadog-delivery-rollback 0 1 0 0
run_case datadog-timeout-rollback 0 0 1 0
run_case datadog-signal-rollback 0 0 0 1
run_case datadog-log-pending-keep 0 0 0 0 1
echo "deployment verification script tests passed"
