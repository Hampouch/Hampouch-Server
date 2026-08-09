## Hampouch

### 테스트 실행

```bash
./gradlew test
```

대부분은 H2로 돌고, MySQL 고유 동작을 보는 일부만 Testcontainers로 MySQL 8.0 컨테이너를 띄운다.
컨테이너의 문자셋·콜레이션과 JDBC 시간대·인코딩은 운영 MySQL 설정에 맞춘다.
기본 `test`에서는 MySQL 테스트를 제외한다. 스키마·제약을 실 MySQL로 검증할 때는 전용 명령을 실행한다.

```bash
./gradlew mysqlTest
```

`mysqlTest`는 Docker 데몬을 먼저 확인하며, 사용할 수 없으면 실패한다.

colima는 소켓이 기본 경로에 없어 자동 검출이 안 된다. 지정해서 실행한다.

```bash
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew mysqlTest
```
