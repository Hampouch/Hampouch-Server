package Hampouch.server.global.mysql;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 마이그레이션 중간 상태를 재현하려고 별도 스키마 생성 권한이 필요한 테스트에 붙인다.
 * 대부분의 MySQL 테스트가 공유하는 {@link MySqlContainerTest}와는 별도의 컨테이너를 띄워
 * (설정 조합이 다르면 스프링 컨텍스트/컨테이너가 분리된다) 이 권한 확장이 다른 테스트에
 * 영향을 주지 않게 격리한다. mysqlTest, CI에서 실행되고 기본 test에서는 제외된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("mysql")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@Import(MySqlAdminContainerConfig.class)
public @interface MySqlSchemaTransitionTest {
}