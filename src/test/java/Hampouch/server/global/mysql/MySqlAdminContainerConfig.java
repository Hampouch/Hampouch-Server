package Hampouch.server.global.mysql;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.BindMode;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 마이그레이션 중간 상태(V1만 적용된 상태, 중복 데이터가 있는 상태 등)를 재현하려고
 * 별도 스키마를 만들어야 하는 극소수 테스트 전용 컨테이너 설정.
 *
 * 대부분의 MySQL 테스트가 공유하는 MySqlContainerConfig의 기본 계정은 자신의 기본
 * 스키마에만 권한이 있어 CREATE DATABASE 같은 관리자 작업을 할 수 없다. 이 권한 확장은
 * 마이그레이션 전환 경로를 검증하는 테스트에만 필요하므로, 공용 컨테이너/설정에는
 * 손대지 않고 별도 컨테이너를 하나 더 띄운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlAdminContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlAdminContainer() {
        return new MySQLContainer(MySqlContainerConfig.MYSQL_IMAGE)
                // withInitScript()는 컨테이너의 일반 앱 계정(test/test)으로 접속해서 실행되므로
                // 그 계정이 자기 자신에게 권한을 부여할 수는 없다 (Access denied 발생).
                // 대신 공식 MySQL 이미지가 최초 부팅 시 root 권한으로 자동 실행해주는
                // /docker-entrypoint-initdb.d/ 메커니즘을 이용한다.
                .withClasspathResourceMapping(
                        "mysql-test-init.sql",
                        "/docker-entrypoint-initdb.d/init.sql",
                        BindMode.READ_ONLY)
                .withCommand(
                        "--character-set-server=" + MySqlContainerConfig.MYSQL_CHARACTER_SET,
                        "--collation-server=" + MySqlContainerConfig.MYSQL_COLLATION)
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("serverTimezone", MySqlContainerConfig.JDBC_TIME_ZONE)
                .withUrlParam("characterEncoding", MySqlContainerConfig.JDBC_CHARACTER_ENCODING);
    }
}