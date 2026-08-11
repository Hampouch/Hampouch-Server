package Hampouch.server.global.mysql;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 실 MySQL 컨테이너를 데이터소스로 물리는 테스트 설정. @ServiceConnection이 접속 정보를 만들어
 * 테스트 기본 설정(src/test/resources/application.yml)의 H2 url·드라이버보다 우선한다.
 * DB 동작에 영향을 주는 이미지 태그와 문자 설정은 운영 docker-compose.prod.yml에 맞춘다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    static final String MYSQL_IMAGE = "mysql:8.0";
    static final String MYSQL_CHARACTER_SET = "utf8mb4";
    static final String MYSQL_COLLATION = "utf8mb4_unicode_ci";
    static final String JDBC_TIME_ZONE = "Asia/Seoul";
    static final String JDBC_CHARACTER_ENCODING = "utf8";

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(MYSQL_IMAGE)
                .withCommand(
                        "--character-set-server=" + MYSQL_CHARACTER_SET,
                        "--collation-server=" + MYSQL_COLLATION)
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("serverTimezone", JDBC_TIME_ZONE)
                .withUrlParam("characterEncoding", JDBC_CHARACTER_ENCODING);
    }
}
