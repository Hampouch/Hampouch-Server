package Hampouch.server.global.mysql;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 배포 시나리오와 동일한 "기존 V1 스키마를 version 1로 baseline한 뒤
 * V1 이후의 모든 마이그레이션이 실행되는지"를 검증한다.
 *
 * 별도 스키마 생성 권한이 필요해 {@link MySqlSchemaTransitionTest}(전용 격리 컨테이너)를
 * 사용한다 - 대부분의 MySQL 테스트가 쓰는 공용 컨테이너에는 영향이 없다.
 */
@MySqlSchemaTransitionTest
class MySqlBaselineTransitionTest {

    private static final String SCHEMA = "baseline_transition_test";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("V1과 동일한 기존 스키마는 version 1로 baseline된 뒤 이후의 모든 마이그레이션이 실행된다")
    void baselinesExistingSchemaThenAppliesRemainingMigrations() {
        MySqlTestSchemas.IsolatedSchema schema = MySqlTestSchemas.create(jdbc, SCHEMA);

        try {
            // 1) "Flyway 도입 전, 이미 V1과 동일한 스키마가 존재하던 상태"를 재현한다.
            // 별도 부트스트랩 히스토리 테이블로 V1만 적용한 뒤 그 히스토리 테이블 자체를
            // 지워서 "운영 도입 이력이 없는, 스키마만 V1과 같은 기존 DB" 상태를 만든다.
            Flyway.configure()
                    .dataSource(schema.jdbcUrl(), schema.username(), schema.password())
                    .table("flyway_bootstrap_history")
                    .locations("classpath:db/migration")
                    .target("1")
                    .load()
                    .migrate();
            schema.jdbc().execute("DROP TABLE flyway_bootstrap_history");

            // 2) 운영 배포 시나리오와 동일하게: baselineVersion(1)로 baseline한 뒤 migrate.
            // V1은 재실행하지 않고 그 이후의 모든 마이그레이션을 적용해야 한다.
            Flyway flyway = Flyway.configure()
                    .dataSource(schema.jdbcUrl(), schema.username(), schema.password())
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .locations("classpath:db/migration")
                    .load();
            MigrateResult result = flyway.migrate();

            Integer baselineRow = schema.jdbc().queryForObject("""
                    select count(*) from flyway_schema_history
                    where version = '1' and type = 'BASELINE' and success = 1
                    """, Integer.class);
            assertThat(baselineRow).as("version 1이 BASELINE으로 기록되어야 한다").isEqualTo(1);

            List<String> appliedSqlVersions = schema.jdbc().queryForList("""
                    select version from flyway_schema_history
                    where type = 'SQL' and success = 1 order by installed_rank
                    """, String.class);
            assertThat(result.migrationsExecuted)
                    .as("실행된 마이그레이션 수와 SQL 이력 수가 같아야 한다")
                    .isEqualTo(appliedSqlVersions.size());
            assertThat(flyway.info().pending())
                    .as("V1 이후 발견된 마이그레이션이 남김없이 적용되어야 한다")
                    .isEmpty();
            assertThat(appliedSqlVersions)
                    .as("V1은 baseline으로만 기록되고 현재 브랜치의 후속 마이그레이션은 SQL로 기록되어야 한다")
                    .doesNotContain("1")
                    .contains("2", "3", "5", "6", "7");

            // 3) V3에서 도입한 제약 이름까지 실제 스키마에 반영됐는지 확인
            List<String> uniqueConstraints = schema.jdbc().queryForList("""
                    select distinct constraint_name from information_schema.table_constraints
                    where table_schema = ? and table_name = 'users' and constraint_type = 'UNIQUE'
                    """, String.class, SCHEMA);
            assertThat(uniqueConstraints).contains("uk_user_email", "uk_user_nickname");
        } finally {
            MySqlTestSchemas.drop(jdbc, SCHEMA);
        }
    }
}
