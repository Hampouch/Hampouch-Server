package Hampouch.server.global.mysql;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2__add_refresh_token_hash_unique.sql이 기존에 중복 저장된 token_hash를 실제로
 * 정리한 뒤 unique 제약을 생성하는지 검증한다.
 *
 * 공유 컨테이너 안에 별도 스키마를 만들어 "V1까지만 적용되어 있고 token_hash 중복
 * 데이터가 있는 상태"를 재현한다 (기본 스키마 test는 컨텍스트 부팅 시 이미 V1~V3까지
 * 다 적용되어 있어 이 상태를 만들 수 없다).
 */
@MySqlSchemaTransitionTest
class RefreshTokenHashMigrationTest {

    private static final String SCHEMA = "refresh_token_dedup_test";

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        MySqlTestSchemas.drop(jdbc, SCHEMA);
    }

    @Test
    @DisplayName("중복 token_hash가 있는 V1 스키마에서도 V2가 중복을 정리하고 unique 제약을 만든다")
    void migratesV1SchemaWithDuplicateTokenHash() {
        MySqlTestSchemas.IsolatedSchema schema = MySqlTestSchemas.create(jdbc, SCHEMA);

        // 1) V1까지만 적용 - token_hash에 아직 unique 제약이 없는, 배포 전과 같은 상태를 재현
        Flyway.configure()
                .dataSource(schema.jdbcUrl(), schema.username(), schema.password())
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();

        // 2) 같은 token_hash를 가진 중복 행을 직접 심는다.
        // refresh_tokens.user_id에는 FK가 걸려 있지 않아(V1 기준) 별도로 유저를 만들지 않아도 된다.
        schema.jdbc().update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expired_at, revoked, created_at, updated_at)
                VALUES (1, 'duplicated-hash', NOW(), false, NOW(), NOW())
                """);
        schema.jdbc().update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expired_at, revoked, created_at, updated_at)
                VALUES (1, 'duplicated-hash', NOW(), false, NOW(), NOW())
                """);

        Integer duplicateCountBefore = schema.jdbc().queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = 'duplicated-hash'", Integer.class);
        assertThat(duplicateCountBefore).isEqualTo(2);

        // 3) 나머지 마이그레이션(V2, V3)을 마저 적용한다. V2에 중복 정리 DML이 없다면 여기서 실패해야 한다.
        MigrateResult result = Flyway.configure()
                .dataSource(schema.jdbcUrl(), schema.username(), schema.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.success).isTrue();

        Integer duplicateCountAfter = schema.jdbc().queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = 'duplicated-hash'", Integer.class);
        assertThat(duplicateCountAfter)
                .as("V2의 중복 정리 DML이 중복 행을 하나만 남기고 정리해야 한다")
                .isEqualTo(1);

        Integer constraintExists = schema.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'refresh_tokens' AND CONSTRAINT_NAME = 'uk_refresh_token_hash'
                """, Integer.class, SCHEMA);
        assertThat(constraintExists)
                .as("uk_refresh_token_hash 제약이 실제로 생성되어야 한다")
                .isEqualTo(1);
    }
}