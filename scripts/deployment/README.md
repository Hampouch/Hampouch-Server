# 운영 배포 검증과 롤백

`deploy-and-verify.sh`는 GitHub Actions가 운영 EC2에 새 이미지를 전송한 뒤 실행한다.

- IMDSv2로 인스턴스가 `t3.small`인지 확인하고 AMI, OS, 아키텍처, Docker, Compose 버전을 기록한다.
- 전송된 Compose 체크섬과 렌더링 결과, SHA 이미지와 실행 컨테이너 이미지가 일치하는지 확인한다.
- 앱과 MySQL health, DB를 포함한 앱 readiness, MySQL의 읽기 전용 `SELECT 1`, 호스트와 컨테이너 메모리를 확인한다.
- Datadog 서비스가 있으면 Agent의 MySQL, OpenMetrics, HTTP check를 확인한다.
- 검증 실패 시 배포 전에 실행되던 이미지 ID를 `latest`로 지정하고 앱 컨테이너를 다시 만든 뒤 health를 확인한다.
- `flyway_schema_history`에 새 마이그레이션 적용이 감지되면 구이미지로의 자동 롤백은 수행하지 않는다.

기본 임계치는 호스트 가용 메모리 256MB 이상, 각 컨테이너 메모리 상한 대비 사용률 90% 이하이다. 운영 실측 후 승인된 값은 `MIN_AVAILABLE_MEMORY_MB`, `MAX_CONTAINER_MEMORY_PERCENT`로 변경한다.

## Flyway 최초 운영 전환

기존 운영 DB에 `flyway_schema_history`가 없을 때만 배포 담당자와 시간을 맞춰 아래 순서로 전환한다.

1. 대상 DB가 기존 운영 스키마인지 확인하고 운영 EC2 `.env`의 `FLYWAY_BASELINE_ON_MIGRATE`를 `true`로 바꾼다.
2. 배포 후 앱 readiness가 통과하고 `flyway_schema_history`에 version `1`, type `BASELINE`, success `1`인 행이 생겼는지 읽기 전용으로 확인한다.
3. 확인 직후 `.env` 값을 `false`로 되돌린다. 다음 배포부터는 Flyway가 기록된 버전 이후의 마이그레이션만 실행한다.

빈 DB에서는 이 값을 `false`로 유지한다. 이 경우 V1 SQL이 전체 스키마를 생성한다.

## 실제 Datadog 도착과 모니터 검증

Datadog 서비스가 있는 배포에서는 `datadog_verification.py deployment`가 추가로 실행된다.

1. 앱과 MySQL의 컨테이너 표준 출력에 이번 SHA를 포함한 배포 표식을 남긴다.
2. 배포 시작 시각 이후에 호스트 메모리, 앱 컨테이너 메모리, 앱 컨테이너 uptime, JVM 메모리, MySQL 연결 지표가 도착했는지 Datadog Metrics API로 확인한다.
3. 같은 시각 이후 앱과 MySQL의 배포 표식이 Datadog Logs API에서 조회되는지 확인한다.
4. `datadog-verification.json`에 기록된 여섯 모니터의 이름, 종류, 쿼리, 임계치, 필수 옵션과 태그가 실제 설정과 같은지 확인한다.
5. 각 모니터가 `published` 상태이고 활성 downtime이 없으며, 승인된 알림 수신처가 메시지에 포함됐는지 확인한다.

수집 지연 검증은 API 응답시간과 polling 간격을 모두 포함해 최대 5분만 기다린다. 이미 도착한 항목은 다음 polling에서 제외하고 미도착 항목을 즉시 로그에 남긴다. `DD_DATA_VERIFY_ATTEMPTS`와 `DD_DATA_VERIFY_INTERVAL_SECONDS`로 polling을 조정할 수 있고, `DD_DATA_VERIFY_TIMEOUT_SECONDS`는 1~300초 안에서만 줄일 수 있다. 검증 프로세스는 310초에 강제 종료되며 timeout이나 취소 신호에서도 로그 프로브를 정리하고 앱 롤백을 실행한다. CD job 전체 상한은 빌드와 전송을 포함해 20분이다. 하나라도 확인되지 않으면 배포는 실패하고 앱 롤백을 실행한다.

운영 EC2의 `.env`에 다음 값을 설정한다. 값은 저장소, PR, GitHub Actions 로그에 넣지 않는다.

- `DD_API_KEY`는 Agent 전송과 전용 service check 제출에 사용한다.
- `DD_APP_KEY`는 `timeseries_query`, `logs_read_data`, `monitors_read`, `monitors_downtime` 권한만 가진 Application Key를 사용한다.
- `DD_MONITOR_CONTAINER_MEMORY_ID`, `DD_MONITOR_CONTAINER_OOM_ID`, `DD_MONITOR_CONTAINER_RESTART_ID`, `DD_MONITOR_APP_READINESS_ID`, `DD_MONITOR_MYSQL_CONNECTIONS_ID`, `DD_ALERT_TEST_MONITOR_ID`에는 실제 모니터 ID를 넣는다.
- `DD_REQUIRED_NOTIFICATION_HANDLES`에는 담당자가 승인한 Datadog 알림 수신처를 쉼표로 구분해 넣는다.

배포 데이터 도착성 검증은 실행 중인 앱 컨테이너에서 지속적으로 수집되는
`container.uptime`을 사용한다. `container.restarts`는 배포 성공 조건이 아니라
실제 컨테이너 재시작을 감지하는 모니터에만 사용한다.

모니터 쿼리와 임계치는 `datadog-verification.json`이 정본이다. 컨테이너 재시작 모니터는 `container.restarts`가 끊겨도 침묵하지 않고 No Data를 알리도록 `on_missing_data=show_and_notify_no_data`를 사용한다. 변경하려면 실제 Datadog 설정과 이 파일을 함께 변경하고 자원 임계치와 알림 수신처는 담당자 승인을 먼저 받는다.

## Alert와 Recovery 수신 시험

`.github/workflows/datadog-alert-path.yml`의 매일 10:00 KST 예약 실행과 수동 실행은 실제 앱이나 DB를 중단하지 않는다. `hampouch.alert_path` service check에 고유한 `signal_id`를 붙여 CRITICAL을 전송한 뒤 OK를 전송한다. 전용 모니터가 Alert와 Recovery로 바뀌었는지 확인하고, Discord 채널에서도 같은 `signal_id`와 `phase:alert` 또는 `phase:recovery`가 조회돼야 성공한다. Alert 확인이 실패해도 OK 신호는 반드시 전송한다.

Discord 연결은 다음과 같이 설정한다.

1. 운영 알림 채널에 Discord incoming webhook을 만들고 전체 URL은 1Password와 Datadog Webhooks integration에만 저장한다. Datadog 웹훅 이름은 `hampouch-discord`, payload는 `{"content":"$TEXT_ONLY_MSG"}`로 설정한다.
2. 여섯 모니터의 알림 메시지에 `@webhook-hampouch-discord`를 넣고 운영 EC2 `.env`의 `DD_REQUIRED_NOTIFICATION_HANDLES`에도 같은 값을 넣는다.
3. 알림 시험 모니터의 메시지는 Alert일 때 `HAMPOUCH_ALERT_TEST phase:alert signal_id:{{signal_id.name}}`, Recovery일 때 `HAMPOUCH_ALERT_TEST phase:recovery signal_id:{{signal_id.name}}`가 포함되게 설정한다.
4. 수신 확인 전용 Discord 봇을 같은 서버에 추가한다. 봇에는 해당 채널의 View Channel과 Read Message History만 허용하고, Developer Portal에서 Message Content Intent를 켠다.
5. 봇 토큰은 1Password와 운영 EC2 `.env`에만 저장한다. 운영 EC2 `.env`에 `DISCORD_BOT_TOKEN`, `DISCORD_CHANNEL_ID`, `DISCORD_WEBHOOK_ID`를 설정한다. `DISCORD_WEBHOOK_ID`는 incoming webhook URL에서 `/webhooks/` 바로 뒤의 숫자다.

검증기는 고정된 Discord API 주소에서 최신 메시지 최대 100개만 읽는다. 설정한 incoming webhook이 작성한 메시지인지 확인한 뒤 정확한 `signal_id`와 `phase`가 모두 있는 경우에만 실제 수신으로 인정한다. 봇 토큰이나 Discord webhook 전체 URL을 저장소, PR, GitHub Actions 로그에 넣지 않는다.

GitHub Actions가 매일 10:00 KST에 운영 EC2에 배포된 검증기를 실행한다. 예약 워크플로 정의는 기본 브랜치에서 읽지만 저장소를 checkout하거나 파일을 EC2로 복사하지 않으므로, 시험 대상은 최근 main 배포가 설치한 운영 파일로 고정된다. 같은 워크플로를 수동 실행해 즉시 재검증할 수도 있다.

## 테스트 경계

PR CI는 가짜 Docker 명령으로 정상 배포와 롤백, Datadog Agent 갱신을 재현한다. 가짜 Datadog·Discord 응답으로 새 데이터 시각 판정, downtime 차단, 모니터 Alert와 Recovery, Discord 메시지 출처와 식별자, 실패 뒤 OK 전송을 확인하고, 별도 계약 테스트로 GitHub 예약 실행이 10:00 KST에 유지되면서 저장소 파일을 운영 EC2로 복사하지 않는지 확인한다. 실제 EC2, Datadog 계정, Discord 알림 수신은 운영 배포와 예약 Action에서만 확인한다. #119의 Testcontainers는 MySQL 버전과 문자 설정 호환성만 담당한다.
