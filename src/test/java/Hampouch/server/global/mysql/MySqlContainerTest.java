package Hampouch.server.global.mysql;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * H2 대신 실 MySQL 컨테이너 위에서 도는 테스트에 붙인다.
 * 설정을 이 한 곳에 모아 두는 이유는 스프링이 설정 조합을 키로 컨텍스트를 캐시하기 때문이다 —
 * 클래스마다 프로퍼티를 따로 적으면 키가 갈려 컨테이너가 클래스 수만큼 뜬다.
 * 기본 test에서는 제외되고 mysqlTest와 CI에서 실행한다. Docker 데몬이 없으면 실행 전에 실패한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("mysql")
@SpringBootTest(properties = {
        // 테스트 기본 설정이 H2Dialect로 못 박아 둔 것을 여기서만 되돌린다. 남겨 두면 MySQL에 H2 문법을 보낸다.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@Import(MySqlContainerConfig.class)
public @interface MySqlContainerTest {
}
