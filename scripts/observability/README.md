# Datadog 운영 절차

## 도입 전 기준값

EC2에서 앱과 MySQL만 실행한 상태로 다음 명령을 실행하고 결과를 작업 기록에 남긴다.

```bash
scripts/observability/collect-baseline.sh
```

호스트 가용 메모리, 루트 디스크, Compose 서비스 상태와 컨테이너별 CPU·메모리를 기록한다.

## 운영 환경 변수

운영 EC2의 `.env`에 아래 두 값만 추가한다. API 키는 저장소, PR 본문, CI/CD 로그에 넣지 않는다.

```dotenv
DD_API_KEY=<Datadog API key>
DD_SITE=datadoghq.com
```

## 배포와 검증

```bash
docker compose -f docker-compose.prod.yml up -d
scripts/observability/verify-datadog.sh
scripts/observability/collect-baseline.sh
```

도입 전후 결과를 비교해 `hampouch-server`, `hampouch-mysql`, `hampouch-datadog`이 각 메모리 상한 안에서 동작하는지 확인한다. Datadog에서는 다음 항목을 확인한다.

- Infrastructure의 Containers에서 세 컨테이너의 CPU·메모리·디스크 I/O·재시작 횟수가 보인다.
- Logs에서 `service:hampouch-server`와 `service:hampouch-mysql` 로그가 보인다.
- Metrics Explorer에서 `hampouch.jvm.memory.used`, `hampouch.process.cpu.usage`, `hampouch.hikaricp.connections.active`가 보인다.
- `hampouch.openmetrics.health`와 `http.can_connect`(`instance:hampouch_readiness`) 서비스 체크가 정상 상태다.

## 모니터

첫 데이터가 들어온 뒤 다음 네 가지 모니터를 만들고 실제 알림을 한 번 발생시켜 수신 경로를 확인한다.

1. 각 컨테이너의 `container.memory.working_set / container.memory.limit`가 5분 동안 85%를 넘으면 경고한다.
2. `container.memory.oom_events`가 0보다 커지면 즉시 경고한다.
3. `container.restarts`가 증가하면 경고한다.
4. `http.can_connect`의 `instance:hampouch_readiness`가 CRITICAL이거나 데이터가 끊겨 No Data가 되면 경고한다.

## 비활성화와 롤백

Agent가 앱이나 MySQL의 안정성을 해치면 먼저 Agent만 중단한다.

```bash
docker compose -f docker-compose.prod.yml stop datadog
```

앱과 MySQL은 그대로 유지된다. 원인을 확인한 뒤 Agent의 메모리 상한이나 수집 범위를 조정하고 다시 시작한다.
