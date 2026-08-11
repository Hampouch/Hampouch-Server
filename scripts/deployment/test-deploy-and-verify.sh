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
            shift 2
            case "${1:-}" in
                config)
                    if [ "${2:-}" = "--services" ]; then
                        printf 'db\napp\ndatadog\n'
                    fi
                    ;;
                up)
                    write_state current_image "$(read_state latest_image)"
                    printf 'compose-up\n' >>"$FAKE_STATE_DIR/events"
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
                *".Image"*) read_state current_image ;;
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
            local exec_env=""
            if [ "${1:-}" = "-e" ]; then
                exec_env="${2:-}"
                shift 2
            fi
            local container="${1:-}"
            shift || true
            case "$container" in
                hampouch-server)
                    if [ -n "$exec_env" ]; then
                        printf 'marker:%s:%s\n' "$container" "${exec_env#HAMPOUCH_DEPLOY_MARKER=}" \
                            >>"$FAKE_STATE_DIR/events"
                        return 0
                    fi
                    if [ "${FAKE_APP_CHECK_FAIL:-0}" = "1" ]; then
                        return 1
                    fi
                    echo '{"status":"UP"}'
                    ;;
                hampouch-mysql)
                    if [ -n "$exec_env" ]; then
                        printf 'marker:%s:%s\n' "$container" "${exec_env#HAMPOUCH_DEPLOY_MARKER=}" \
                            >>"$FAKE_STATE_DIR/events"
                        return 0
                    fi
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

python3() {
    [ "${1:-}" = "scripts/deployment/datadog_verification.py" ] \
        || fail "지원하지 않는 Python 실행: $*"
    printf 'datadog-verification\n' >>"$FAKE_STATE_DIR/events"
    [ "${FAKE_DATADOG_DELIVERY_FAIL:-0}" != "1" ]
}

export -f curl docker fail free python3 read_state sha256sum sleep write_state
trap cleanup EXIT

run_case() {
    local case_name="$1"
    local app_check_fail="$2"
    local datadog_delivery_fail="$3"
    local case_dir="$test_root/$case_name"
    local state_dir="$case_dir/state"
    local log_file="$case_dir/run.log"
    local expected_failure=0

    if [ "$app_check_fail" = "1" ] || [ "$datadog_delivery_fail" = "1" ]; then
        expected_failure=1
    fi

    mkdir -p "$state_dir" "$case_dir/scripts/deployment" "$case_dir/scripts/observability"
    printf 'services:\n  app: {}\n  db: {}\n  datadog: {}\n' >"$case_dir/docker-compose.prod.yml"
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

    if (
        cd "$case_dir"
        DEPLOY_SHA_TAG=abcdef12 \
            EXPECTED_COMPOSE_SHA256=compose-hash \
            HEALTH_ATTEMPTS=1 \
            HEALTH_INTERVAL_SECONDS=0 \
            OS_RELEASE_FILE="$case_dir/os-release" \
            MEMINFO_FILE="$case_dir/meminfo" \
            bash "$deployment_script"
    ) >"$log_file" 2>&1; then
        [ "$expected_failure" = "0" ] || fail "실패 시나리오가 성공했습니다"
    else
        [ "$expected_failure" = "1" ] || {
            sed -n '1,240p' "$log_file" >&2
            fail "성공 시나리오가 실패했습니다"
        }
    fi

    grep -qx 'health:hampouch-datadog' "$state_dir/events" \
        || fail "Datadog health 대기가 실행되지 않았습니다"
    grep -qx 'verify-datadog' "$state_dir/events" \
        || fail "Datadog Agent 검증이 실행되지 않았습니다"

    if [ "$app_check_fail" = "0" ]; then
        grep -qx 'marker:hampouch-server:hampouch_deploy_verification sha:abcdef12' "$state_dir/events" \
            || fail "앱 로그 배포 표식이 기록되지 않았습니다"
        grep -qx 'marker:hampouch-mysql:hampouch_deploy_verification sha:abcdef12' "$state_dir/events" \
            || fail "MySQL 로그 배포 표식이 기록되지 않았습니다"
        grep -qx 'datadog-verification' "$state_dir/events" \
            || fail "Datadog 데이터 도착 검증이 실행되지 않았습니다"
    fi

    if [ "$expected_failure" = "0" ]; then
        [ "$(read_state current_image)" = "new-image" ] || fail "새 이미지가 실행되지 않았습니다"
        [ "$(grep -c '^compose-up$' "$state_dir/events")" = "1" ] \
            || fail "성공 배포의 compose 실행 횟수가 다릅니다"
        grep -q '운영 환경 검증을 통과했습니다' "$log_file" || fail "성공 로그가 없습니다"
    else
        [ "$(read_state current_image)" = "old-image" ] || fail "이전 이미지로 롤백되지 않았습니다"
        [ "$(read_state latest_image)" = "old-image" ] || fail "latest가 이전 이미지로 복구되지 않았습니다"
        [ "$(grep -c '^compose-up$' "$state_dir/events")" = "2" ] \
            || fail "롤백 재생성 횟수가 다릅니다"
        grep -q '이전 이미지로 앱 컨테이너를 복구하고 health를 확인했습니다' "$log_file" \
            || fail "롤백 성공 로그가 없습니다"
    fi
}

run_case success 0 0
run_case app-check-rollback 1 0
run_case datadog-delivery-rollback 0 1
echo "deployment verification script tests passed"
