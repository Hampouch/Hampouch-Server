#!/usr/bin/env python3

import argparse
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


class VerificationError(RuntimeError):
    pass


class LogArrivalPending(VerificationError):
    pass


DATADOG_API_BASES = {
    "datadoghq.com": "https://api.datadoghq.com",
    "us3.datadoghq.com": "https://api.us3.datadoghq.com",
    "us5.datadoghq.com": "https://api.us5.datadoghq.com",
    "datadoghq.eu": "https://api.datadoghq.eu",
    "ap1.datadoghq.com": "https://api.ap1.datadoghq.com",
    "ap2.datadoghq.com": "https://api.ap2.datadoghq.com",
    "uk1.datadoghq.com": "https://api.uk1.datadoghq.com",
    "ddog-gov.com": "https://api.ddog-gov.com",
    "us2.ddog-gov.com": "https://api.us2.ddog-gov.com",
}
DISCORD_API_BASE = "https://discord.com/api/v10"
MAX_DEPLOYMENT_TIMEOUT_SECONDS = 300
DEPLOYMENT_TIMEOUT_MESSAGE = "Datadog 배포 데이터 검증의 전체 제한시간을 초과했습니다."
LOG_ARRIVAL_PENDING_EXIT_CODE = 3


def load_env_file(path):
    env_path = Path(path)
    if not env_path.is_file():
        raise VerificationError(f"운영 환경 변수 파일을 찾을 수 없습니다: {env_path}")

    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        os.environ.setdefault(key, value)


def required_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise VerificationError(f"{name}가 운영 환경에 설정되어야 합니다.")
    return value


class DatadogClient:
    def __init__(self, api_key, app_key, site, timeout_seconds=15):
        if site not in DATADOG_API_BASES:
            raise VerificationError(f"지원하지 않는 DD_SITE입니다: {site}")
        self.api_key = api_key
        self.app_key = app_key
        self.api_base = DATADOG_API_BASES[site]
        self.timeout_seconds = timeout_seconds
        self.deadline = None

    def set_deadline(self, deadline):
        self.deadline = deadline

    def request_timeout_seconds(self):
        if self.deadline is None:
            return self.timeout_seconds
        remaining_seconds = self.deadline - time.monotonic()
        if remaining_seconds <= 0:
            raise VerificationError(DEPLOYMENT_TIMEOUT_MESSAGE)
        return max(0.001, min(self.timeout_seconds, remaining_seconds))

    def request(self, method, path, params=None, body=None, require_app_key=True):
        query = urllib.parse.urlencode(params or {}, doseq=True)
        url = f"{self.api_base}{path}"
        if query:
            url = f"{url}?{query}"

        headers = {
            "Accept": "application/json",
            "DD-API-KEY": self.api_key,
        }
        if require_app_key:
            headers["DD-APPLICATION-KEY"] = self.app_key
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(
                request,
                timeout=self.request_timeout_seconds(),
            ) as response:
                payload = response.read()
        except urllib.error.HTTPError as error:
            raise VerificationError(
                f"Datadog API 요청이 실패했습니다: {method} {path}, HTTP {error.code}"
            ) from error
        except (urllib.error.URLError, TimeoutError) as error:
            if self.deadline is not None and time.monotonic() >= self.deadline:
                raise VerificationError(DEPLOYMENT_TIMEOUT_MESSAGE) from error
            raise VerificationError(f"Datadog API에 연결할 수 없습니다: {method} {path}") from error

        if not payload:
            return {}
        try:
            return json.loads(payload)
        except json.JSONDecodeError as error:
            raise VerificationError(f"Datadog API가 JSON이 아닌 응답을 반환했습니다: {path}") from error

    def get(self, path, params=None):
        return self.request("GET", path, params=params)

    def post(self, path, body, require_app_key=True):
        return self.request("POST", path, body=body, require_app_key=require_app_key)


class DiscordEvidenceClient:
    def __init__(self, bot_token, channel_id, webhook_id, timeout_seconds=15):
        if not channel_id.isdigit():
            raise VerificationError("DISCORD_CHANNEL_ID는 숫자 채널 ID여야 합니다.")
        if not webhook_id.isdigit():
            raise VerificationError("DISCORD_WEBHOOK_ID는 숫자 웹훅 ID여야 합니다.")
        self.bot_token = bot_token
        self.channel_id = channel_id
        self.webhook_id = webhook_id
        self.timeout_seconds = timeout_seconds

    def messages(self):
        url = f"{DISCORD_API_BASE}/channels/{self.channel_id}/messages?limit=100"
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/json",
                "Authorization": f"Bot {self.bot_token}",
                "User-Agent": "HampouchDeploymentVerifier/1.0",
            },
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                payload = response.read()
        except urllib.error.HTTPError as error:
            if error.code == 429:
                return []
            if error.code in {401, 403}:
                raise VerificationError("Discord 봇 토큰 또는 채널 읽기 권한이 올바르지 않습니다.") from error
            raise VerificationError(f"Discord 메시지 조회가 실패했습니다: HTTP {error.code}") from error
        except urllib.error.URLError as error:
            raise VerificationError("Discord API에 연결할 수 없습니다.") from error

        try:
            messages = json.loads(payload)
        except json.JSONDecodeError as error:
            raise VerificationError("Discord API가 JSON이 아닌 응답을 반환했습니다.") from error
        if not isinstance(messages, list):
            raise VerificationError("Discord 메시지 응답 형식이 올바르지 않습니다.")
        return messages

    def received(self, signal_id, phase):
        return any(
            discord_message_has_delivery(message, self.webhook_id, signal_id, phase)
            for message in self.messages()
        )


def discord_message_has_delivery(message, webhook_id, signal_id, phase):
    if str(message.get("webhook_id", "")) != webhook_id:
        return False
    searchable = {
        "content": message.get("content"),
        "embeds": message.get("embeds"),
        "components": message.get("components"),
    }
    payload = json.dumps(searchable, ensure_ascii=False)
    return f"signal_id:{signal_id}" in payload and f"phase:{phase}" in payload.lower()


def load_config(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"Datadog 검증 설정을 읽을 수 없습니다: {path}") from error


def normalize_query(value):
    return " ".join(value.split())


def verify_expected_subset(actual, expected, path="options"):
    for key, expected_value in expected.items():
        current_path = f"{path}.{key}"
        if key not in actual:
            omitted_false_default = (
                path == "options"
                and key in {"notify_no_data", "require_full_window"}
                and expected_value is False
            )
            if omitted_false_default:
                continue
            raise VerificationError(f"모니터 설정에 {current_path} 값이 없습니다.")
        actual_value = actual[key]
        if isinstance(expected_value, dict):
            if not isinstance(actual_value, dict):
                raise VerificationError(f"모니터의 {current_path} 형식이 승인된 설정과 다릅니다.")
            verify_expected_subset(actual_value, expected_value, current_path)
        elif actual_value != expected_value:
            raise VerificationError(f"모니터의 {current_path} 값이 승인된 설정과 다릅니다.")


def notification_handles():
    handles = []
    for raw_handle in required_env("DD_REQUIRED_NOTIFICATION_HANDLES").split(","):
        handle = raw_handle.strip()
        if handle:
            handles.append(handle if handle.startswith("@") else f"@{handle}")
    if not handles:
        raise VerificationError("DD_REQUIRED_NOTIFICATION_HANDLES에 알림 수신처가 필요합니다.")
    return handles


def monitor_details(client, monitor_id):
    return client.get(
        f"/api/v1/monitor/{monitor_id}",
        params={"group_states": "all", "with_downtimes": "true"},
    )


def verify_monitor(client, expected, handles):
    monitor_id = required_env(expected["id_env"])
    if not monitor_id.isdigit():
        raise VerificationError(f"{expected['id_env']}는 숫자 모니터 ID여야 합니다.")
    actual = monitor_details(client, monitor_id)
    downtime_matches = client.get(
        f"/api/v2/monitor/{monitor_id}/downtime_matches",
        params={"page[limit]": 1},
    )

    if actual.get("draft_status", "published") != "published":
        raise VerificationError(f"{expected['name']} 모니터가 published 상태가 아닙니다.")
    if actual.get("matching_downtimes") or downtime_matches.get("data"):
        raise VerificationError(f"{expected['name']} 모니터에 활성 downtime이 있습니다.")
    if actual.get("name") != expected["name"]:
        raise VerificationError(f"모니터 {monitor_id}의 이름이 승인된 설정과 다릅니다.")
    if actual.get("type") != expected["type"]:
        raise VerificationError(f"{expected['name']} 모니터 종류가 승인된 설정과 다릅니다.")
    if normalize_query(actual.get("query", "")) != normalize_query(expected["query"]):
        raise VerificationError(f"{expected['name']} 모니터 쿼리 또는 임계치가 승인된 설정과 다릅니다.")

    if set(expected.get("tags", [])) - set(actual.get("tags") or []):
        raise VerificationError(f"{expected['name']} 모니터에 필수 태그가 없습니다.")
    verify_expected_subset(actual.get("options") or {}, expected.get("options", {}))

    message = actual.get("message") or ""
    if any(handle not in message for handle in handles):
        raise VerificationError(f"{expected['name']} 모니터에 승인된 알림 수신처가 없습니다.")
    for fragment in expected.get("message_fragments", []):
        if fragment not in message:
            raise VerificationError(f"{expected['name']} 모니터의 Alert/Recovery 메시지 형식이 다릅니다.")

    print(f"monitor={expected['name']} published=true downtime=false route=true")
    return actual


def verify_all_monitors(client, config):
    handles = notification_handles()
    for expected in config["monitors"]:
        verify_monitor(client, expected, handles)


def metric_has_fresh_point(response, from_epoch):
    threshold_ms = from_epoch * 1000
    for series in response.get("series") or []:
        for point in series.get("pointlist") or []:
            if len(point) >= 2 and point[1] is not None and point[0] >= threshold_ms:
                return True
    return False


def iso_timestamp(epoch_seconds):
    return datetime.fromtimestamp(epoch_seconds, timezone.utc).isoformat().replace("+00:00", "Z")


def pending_deployment_data(client, config, from_epoch, sha, previous_pending=None):
    pending = []
    selected = None if previous_pending is None else set(previous_pending)
    now_epoch = int(time.time())
    for metric in config["metrics"]:
        key = f"metric:{metric['name']}"
        if selected is not None and key not in selected:
            continue
        response = client.get(
            "/api/v1/query",
            params={"from": from_epoch, "to": now_epoch, "query": metric["query"]},
        )
        if not metric_has_fresh_point(response, from_epoch):
            pending.append(key)

    marker_query = f'"hampouch_deploy_verification" "sha:{sha}"'
    for log in config["logs"]:
        key = f"log:{log['name']}"
        if selected is not None and key not in selected:
            continue
        response = client.post(
            "/api/v2/logs/events/search",
            {
                "filter": {
                    "from": iso_timestamp(from_epoch),
                    "to": "now",
                    "query": f"{log['query']} {marker_query}",
                },
                "page": {"limit": 1},
                "sort": "timestamp",
            },
        )
        if not response.get("data"):
            pending.append(key)
    return pending


def pending_is_log_only(pending):
    return bool(pending) and all(key.startswith("log:") for key in pending)


def deployment_data_error(message, pending):
    if pending_is_log_only(pending):
        return LogArrivalPending(f"{message} 미도착: {', '.join(pending)}")
    return VerificationError(message)


def wait_for_deployment_data(
    client,
    config,
    from_epoch,
    sha,
    attempts,
    interval_seconds,
    deadline,
    clock=None,
    sleeper=None,
):
    clock = clock or time.monotonic
    sleeper = sleeper or time.sleep
    pending = None
    for attempt in range(1, attempts + 1):
        if clock() >= deadline:
            raise deployment_data_error(DEPLOYMENT_TIMEOUT_MESSAGE, pending)
        try:
            pending = pending_deployment_data(client, config, from_epoch, sha, pending)
        except VerificationError as error:
            if str(error) == DEPLOYMENT_TIMEOUT_MESSAGE and pending_is_log_only(pending):
                raise deployment_data_error(DEPLOYMENT_TIMEOUT_MESSAGE, pending) from error
            raise
        if clock() >= deadline:
            raise deployment_data_error(DEPLOYMENT_TIMEOUT_MESSAGE, pending)
        if not pending:
            print(
                "이번 배포 뒤의 Datadog 호스트·컨테이너·JVM·MySQL 지표와 앱·MySQL 로그를 확인했습니다.",
                flush=True,
            )
            return
        print(
            f"Datadog 데이터 도착 대기 {attempt}/{attempts}: {', '.join(pending)}",
            flush=True,
        )
        if attempt < attempts:
            remaining_seconds = deadline - clock()
            if remaining_seconds <= 0:
                raise deployment_data_error(DEPLOYMENT_TIMEOUT_MESSAGE, pending)
            sleeper(min(interval_seconds, remaining_seconds))
    raise deployment_data_error(
        "이번 배포에서 생성된 필수 Datadog 지표 또는 로그가 제한 시간 안에 도착하지 않았습니다.", pending
    )


def validate_api_key(client):
    response = client.request("GET", "/api/v1/validate", require_app_key=False)
    if response.get("valid") is not True:
        raise VerificationError("DD_API_KEY가 유효하지 않습니다.")


def submit_alert_path_check(client, signal_id, status):
    phase = "alert" if status == 2 else "recovery"
    client.post(
        "/api/v1/check_run",
        [
            {
                "check": "hampouch.alert_path",
                "host_name": "hampouch-alert-verifier",
                "message": f"Hampouch alert path {phase}: {signal_id}",
                "status": status,
                "tags": ["env:prod", f"signal_id:{signal_id}"],
                "timestamp": int(time.time()),
            }
        ],
        require_app_key=False,
    )


def monitor_group_reached(monitor, signal_id, expected_status, sent_at):
    groups = (monitor.get("state") or {}).get("groups") or {}
    for group_name, state in groups.items():
        if signal_id not in group_name or state.get("status") != expected_status:
            continue
        timestamp_field = "last_triggered_ts" if expected_status == "Alert" else "last_resolved_ts"
        timestamp = state.get(timestamp_field)
        if timestamp is not None and timestamp >= sent_at:
            return True
    return False


def wait_for_monitor_state(client, monitor_id, signal_id, expected_status, sent_at, attempts, interval_seconds):
    for attempt in range(1, attempts + 1):
        if monitor_group_reached(monitor_details(client, monitor_id), signal_id, expected_status, sent_at):
            print(f"alert_test monitor_status={expected_status}")
            return
        if attempt < attempts:
            time.sleep(interval_seconds)
    raise VerificationError(f"전용 알림 시험 모니터가 {expected_status} 상태로 전환되지 않았습니다.")


def wait_for_discord_delivery(client, signal_id, phase, attempts, interval_seconds):
    for attempt in range(1, attempts + 1):
        if client.received(signal_id, phase):
            print(f"alert_test discord_delivery={phase}")
            return
        if attempt < attempts:
            time.sleep(interval_seconds)
    raise VerificationError(f"Discord 채널에서 {phase} 알림 수신을 확인하지 못했습니다.")


def run_alert_path(client, config, args, discord_client=None):
    now_epoch = int(time.time())
    validate_api_key(client)
    verify_all_monitors(client, config)
    monitor_id = required_env("DD_ALERT_TEST_MONITOR_ID")
    discord_client = discord_client or create_discord_client()
    signal_id = args.signal_id or f"{now_epoch}-{secrets.token_hex(4)}"
    if not re.fullmatch(r"[A-Za-z0-9_-]{8,80}", signal_id):
        raise VerificationError("알림 시험 signal_id 형식이 올바르지 않습니다.")

    alert_sent_at = int(time.time())
    submit_alert_path_check(client, signal_id, 2)
    alert_error = None
    recovery_error = None
    try:
        wait_for_monitor_state(
            client, monitor_id, signal_id, "Alert", alert_sent_at, args.attempts, args.interval_seconds
        )
        wait_for_discord_delivery(discord_client, signal_id, "alert", args.attempts, args.interval_seconds)
    except VerificationError as error:
        alert_error = error
    finally:
        recovery_sent_at = int(time.time())
        try:
            submit_alert_path_check(client, signal_id, 0)
            wait_for_monitor_state(
                client,
                monitor_id,
                signal_id,
                "OK",
                recovery_sent_at,
                args.attempts,
                args.interval_seconds,
            )
            wait_for_discord_delivery(
                discord_client, signal_id, "recovery", args.attempts, args.interval_seconds
            )
        except VerificationError as error:
            recovery_error = error

    if alert_error and recovery_error:
        raise VerificationError(f"{alert_error} 복구 신호 검증도 실패했습니다: {recovery_error}")
    if alert_error:
        raise alert_error
    if recovery_error:
        raise recovery_error

    print("전용 테스트 신호의 Alert·Recovery와 실제 알림 채널 수신을 확인했습니다.")


def create_client():
    return DatadogClient(
        required_env("DD_API_KEY"),
        required_env("DD_APP_KEY"),
        os.environ.get("DD_SITE", "datadoghq.com"),
        int(os.environ.get("DD_API_TIMEOUT_SECONDS", "15")),
    )


def create_discord_client():
    return DiscordEvidenceClient(
        required_env("DISCORD_BOT_TOKEN"),
        required_env("DISCORD_CHANNEL_ID"),
        required_env("DISCORD_WEBHOOK_ID"),
        int(os.environ.get("DISCORD_API_TIMEOUT_SECONDS", "15")),
    )


def build_parser():
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--config", default=str(script_dir / "datadog-verification.json"))
    subparsers = parser.add_subparsers(dest="command", required=True)

    deployment = subparsers.add_parser("deployment")
    deployment.add_argument("--from-epoch", type=int, required=True)
    deployment.add_argument("--sha", required=True)
    deployment.add_argument("--attempts", type=int)
    deployment.add_argument("--interval-seconds", type=int)
    deployment.add_argument("--timeout-seconds", type=int)

    alert_path = subparsers.add_parser("alert-path")
    alert_path.add_argument("--attempts", type=int)
    alert_path.add_argument("--interval-seconds", type=int)
    alert_path.add_argument("--signal-id")
    return parser


def main():
    args = build_parser().parse_args()
    try:
        load_env_file(args.env_file)
        config = load_config(args.config)
        client = create_client()
        if args.command == "deployment":
            args.attempts = (
                args.attempts
                if args.attempts is not None
                else int(os.environ.get("DD_DATA_VERIFY_ATTEMPTS", "24"))
            )
            args.interval_seconds = (
                args.interval_seconds
                if args.interval_seconds is not None
                else int(os.environ.get("DD_DATA_VERIFY_INTERVAL_SECONDS", "10"))
            )
            args.timeout_seconds = (
                args.timeout_seconds
                if args.timeout_seconds is not None
                else int(
                    os.environ.get(
                        "DD_DATA_VERIFY_TIMEOUT_SECONDS",
                        str(MAX_DEPLOYMENT_TIMEOUT_SECONDS),
                    )
                )
            )
            if args.attempts <= 0:
                raise VerificationError("Datadog 배포 데이터 검증 시도 횟수는 1 이상이어야 합니다.")
            if args.interval_seconds < 0:
                raise VerificationError("Datadog 배포 데이터 검증 간격은 0초 이상이어야 합니다.")
            if not 1 <= args.timeout_seconds <= MAX_DEPLOYMENT_TIMEOUT_SECONDS:
                raise VerificationError(
                    f"Datadog 배포 데이터 검증 제한시간은 1~{MAX_DEPLOYMENT_TIMEOUT_SECONDS}초여야 합니다."
                )
            if not re.fullmatch(r"[0-9a-fA-F]{7,40}", args.sha):
                raise VerificationError("배포 SHA 형식이 올바르지 않습니다.")
            deadline = time.monotonic() + args.timeout_seconds
            client.set_deadline(deadline)
            print(f"Datadog 배포 데이터 검증 제한시간={args.timeout_seconds}초", flush=True)
            validate_api_key(client)
            verify_all_monitors(client, config)
            wait_for_deployment_data(
                client,
                config,
                args.from_epoch,
                args.sha.lower(),
                args.attempts,
                args.interval_seconds,
                deadline,
            )
        else:
            args.attempts = args.attempts or int(os.environ.get("DD_ALERT_VERIFY_ATTEMPTS", "30"))
            args.interval_seconds = args.interval_seconds or int(
                os.environ.get("DD_ALERT_VERIFY_INTERVAL_SECONDS", "10")
            )
            run_alert_path(client, config, args)
    except LogArrivalPending as error:
        print(f"Datadog 로그 도착만 확인하지 못했습니다: {error}", file=sys.stderr)
        return LOG_ARRIVAL_PENDING_EXIT_CODE
    except (VerificationError, ValueError) as error:
        print(f"Datadog 운영 검증 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
