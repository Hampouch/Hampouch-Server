# 자동 검증 범위

| 구성 | 실행 시점 | 책임 |
| --- | --- | --- |
| `ci.yml` | develop/main 대상 PR, develop push | `dependency-review`, `gradle-test`, `mysql-test`, `deployment-scripts`, `docker-image`를 독립 실행하고 `build`에서 결과 집계 |
| `quality.yml` | develop/main 대상 PR·push, 수동 실행 | Actionlint로 전체 워크플로와 인라인 셸 검사, ShellCheck로 저장소의 모든 셸 스크립트 검사, 미해결 merge marker 차단 |
| `observability.yml` | 관련 운영 설정이 바뀐 develop/main 대상 PR, 수동 실행 | 비밀값 없는 CI 전용 값으로 운영 Compose 렌더링, `Dockerfile.deploy` 이미지 빌드, 앱·MySQL·Datadog 통합 검증 |
| `codeql.yml` | develop/main 대상 PR·push, 주 1회, 수동 실행 | Java 코드의 CodeQL 보안 분석 |
| `dependency-submission.yml` | develop/main push, 수동 실행 | 실제 Gradle 의존성 그래프를 GitHub Dependency Graph에 제출 |
| `datadog-alert-path.yml` | 매일 예약 실행 또는 수동 실행 | 운영 Datadog Alert와 Recovery가 Discord까지 전달되는지 검증 |
| `deploy.yml` | main push 또는 수동 실행 | EC2 배포 후 앱·DB·Datadog을 검증하고 실패 시 이전 이미지로 자동 롤백 |
| `dependabot.yml` | 매주 월요일 | Gradle, GitHub Actions, Docker 업데이트 PR을 develop 대상으로 생성 |

정적 검사는 `quality.yml`, 의존성 변경 차단과 반복 가능한 애플리케이션·스크립트 기능 검증은 `ci.yml`의 독립 job, 운영 Compose와 관측성 통합 검증은 `observability.yml`이 담당한다. PR 화면에서 실패 영역을 바로 구분할 수 있도록 CI 검증은 병렬 실행하고, 필수 검사 이름을 유지하는 `CI / build`가 모든 결과를 최종 집계한다. 같은 검사를 여러 워크플로에서 반복하지 않는다.

## 테스트 실패 확인

`gradle-test`와 `mysql-test`가 실패하면 해당 job의 Gradle 실행 로그에서 실패한 테스트명, 예외 메시지, 원인 체인과 스택 트레이스를 바로 확인한다. 성공 테스트와 표준 출력은 추가로 노출하지 않으며, Gradle 명령을 별도 요약 단계로 감싸지 않아 테스트의 종료 코드가 그대로 job 실패로 반영된다.

전체 테스트 리포트는 실패한 job의 `gradle-test-report` 또는 `mysql-test-report` 아티팩트에서 확인한다. Actions 로그로 원인을 먼저 좁히고, 전체 출력과 테스트별 상세 내역이 필요할 때 HTML 리포트를 사용한다.

CI와 배포 검증은 GitHub 러너와 실제 EC2에서 실행한다. 로컬에서는 Actionlint와 ShellCheck 같은 정적 검사만 확인하고 JUnit·Docker Compose·API 검증을 반복하지 않는다.
