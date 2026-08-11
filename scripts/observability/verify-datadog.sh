#!/usr/bin/env bash
set -euo pipefail

attempts="${VERIFY_ATTEMPTS:-36}"
interval_seconds="${VERIFY_INTERVAL_SECONDS:-5}"

wait_for() {
    local description="$1"
    shift
    local attempt

    for ((attempt = 1; attempt <= attempts; attempt++)); do
        if "$@" >/dev/null 2>&1; then
            echo "$description 확인 완료"
            return 0
        fi
        sleep "$interval_seconds"
    done

    echo "$description 확인 시간이 초과됐습니다." >&2
    "$@"
}

container_is_healthy() {
    local container="$1"
    [ "$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")" = "healthy" ]
}

monitoring_init_succeeded() {
    [ "$(docker inspect --format='{{.State.Status}}:{{.State.ExitCode}}' hampouch-mysql-monitoring-init)" = "exited:0" ]
}

readiness_is_up() {
    docker exec hampouch-server curl -fsS http://localhost:8081/actuator/health/readiness \
        | grep -q '"status":"UP"'
}

prometheus_has_metrics() {
    docker exec hampouch-server curl -fsS http://localhost:8081/actuator/prometheus \
        | grep -Eq '^(jvm_memory_used_bytes|process_cpu_usage)'
}

agent_is_healthy() {
    docker exec hampouch-datadog agent health
}

mysql_has_metrics() {
    local output
    local expectation

    output="$(docker exec hampouch-datadog agent check mysql --check-rate 2>&1)" || {
        printf '%s\n' "$output" >&2
        return 1
    }
    for expectation in \
        mysql.can_connect \
        mysql.performance.queries \
        mysql.performance.threads_connected \
        mysql.performance.threads_running \
        mysql.net.max_connections_available; do
        if ! grep -Fq "$expectation" <<<"$output"; then
            echo "MySQL 수집 결과에서 $expectation 항목을 찾지 못했습니다." >&2
            return 1
        fi
    done
}

openmetrics_check_succeeds() {
    docker exec hampouch-datadog agent check openmetrics --check-rate
}

http_check_succeeds() {
    docker exec hampouch-datadog agent check http_check --check-rate
}

wait_for "MySQL 모니터링 계정 초기화" monitoring_init_succeeded

containers=(hampouch-server hampouch-mysql hampouch-datadog)
for container in "${containers[@]}"; do
    wait_for "$container health" container_is_healthy "$container"
done

wait_for "DB를 포함한 앱 readiness" readiness_is_up
wait_for "Prometheus JVM·프로세스 지표" prometheus_has_metrics
wait_for "Datadog Agent health" agent_is_healthy
wait_for "Datadog MySQL 연결·쿼리 지표" mysql_has_metrics
wait_for "Datadog OpenMetrics check" openmetrics_check_succeeds
wait_for "Datadog HTTP check" http_check_succeeds

echo "Datadog Agent와 MySQL·JVM 메트릭 수집 검증을 통과했습니다."
