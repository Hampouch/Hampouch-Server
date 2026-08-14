package Hampouch.server.global.mysql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3__rename_user_unqiue_constraints.sql이 users.email / users.nickname의 unique 제약
 * 이름을 RENAME INDEX로 정확히 바꿨는지 검증한다: 옛 이름(Hibernate 자동 생성 이름)은
 * 사라지고, 새 이름(uk_user_email, uk_user_nickname)만 남아야 한다.
 *
 * 기본 스키마(test)는 컨텍스트 부팅 시 이미 V1~V3까지 다 적용된 상태이므로 별도 스키마
 * 없이 바로 확인할 수 있다.
 */
@MySqlContainerTest
class UserUniqueConstraintRenameTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("V3 적용 후 users의 unique 제약은 새 이름만 남고 옛 이름은 사라진다")
    void oldConstraintNamesAreGoneAndNewOnesExist() {
        List<String> constraintNames = jdbc.queryForList("""
                SELECT DISTINCT CONSTRAINT_NAME
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND CONSTRAINT_TYPE = 'UNIQUE'
                """, String.class);

        assertThat(constraintNames)
                .as("새 이름으로 명명된 제약이 존재해야 한다")
                .contains("uk_user_email", "uk_user_nickname")
                .as("Hibernate가 자동 생성했던 옛 이름은 더 이상 남아있으면 안 된다")
                .doesNotContain("UK6dotkott2kjsp8vw4d0m25fb7", "UK2ty1xmrrgtn89xt7kyxx6ta7h");
    }
}