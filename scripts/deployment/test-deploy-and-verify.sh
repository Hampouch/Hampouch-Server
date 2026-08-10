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
                        printf 'db\napp\n'
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
                    hampouch-server | hampouch-mysql) return 0 ;;
                    *) return 1 ;;
                esac
            fi
            case "${1:-}" in
                *".Image"*) read_state current_image ;;
                *) echo "healthy" ;;
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
            case "${1:-}" in
                hampouch-server)
                    if [ "${FAKE_APP_CHECK_FAIL:-0}" = "1" ]; then
                        return 1
                    fi
                    echo '{"status":"UP"}'
                    ;;
                hampouch-mysql)
                    echo "1"
                    ;;
                *)
                    fail "지원하지 않는 docker exec 대상: ${1:-}"
                    ;;
            esac
            ;;
        stats)
            if [[ " $* " == *" --format "* ]]; then
                printf 'hampouch-server|10.00%%\nhampouch-mysql|20.00%%\n'
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

export -f curl docker fail free read_state sha256sum sleep write_state
trap cleanup EXIT

run_case() {
    local case_name="$1"
    local app_check_fail="$2"
    local case_dir="$test_root/$case_name"
    local state_dir="$case_dir/state"
    local log_file="$case_dir/run.log"

    mkdir -p "$state_dir"
    printf 'services:\n  app: {}\n  db: {}\n' >"$case_dir/docker-compose.prod.yml"
    printf 'image archive\n' >"$case_dir/hampouch-server.tar"
    printf 'PRETTY_NAME="Test Linux"\n' >"$case_dir/os-release"
    printf 'MemAvailable: 1048576 kB\n' >"$case_dir/meminfo"
    printf 'old-image\n' >"$state_dir/current_image"
    printf 'old-image\n' >"$state_dir/latest_image"
    printf 'new-image\n' >"$state_dir/new_image"
    : >"$state_dir/events"

    export FAKE_STATE_DIR="$state_dir"
    export FAKE_APP_CHECK_FAIL="$app_check_fail"

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
        [ "$app_check_fail" = "0" ] || fail "실패 시나리오가 성공했습니다"
    else
        [ "$app_check_fail" = "1" ] || {
            sed -n '1,240p' "$log_file" >&2
            fail "성공 시나리오가 실패했습니다"
        }
    fi

    if [ "$app_check_fail" = "0" ]; then
        [ "$(read_state current_image)" = "new-image" ] || fail "새 이미지가 실행되지 않았습니다"
        [ "$(wc -l <"$state_dir/events" | tr -d ' ')" = "1" ] || fail "성공 배포의 compose 실행 횟수가 다릅니다"
        grep -q '운영 환경 검증을 통과했습니다' "$log_file" || fail "성공 로그가 없습니다"
    else
        [ "$(read_state current_image)" = "old-image" ] || fail "이전 이미지로 롤백되지 않았습니다"
        [ "$(read_state latest_image)" = "old-image" ] || fail "latest가 이전 이미지로 복구되지 않았습니다"
        [ "$(wc -l <"$state_dir/events" | tr -d ' ')" = "2" ] || fail "롤백 재생성 횟수가 다릅니다"
        grep -q '이전 이미지로 앱 컨테이너를 복구하고 health를 확인했습니다' "$log_file" \
            || fail "롤백 성공 로그가 없습니다"
    fi
}

run_case success 0
run_case rollback 1
echo "deployment verification script tests passed"
