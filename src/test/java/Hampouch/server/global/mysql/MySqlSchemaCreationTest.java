package Hampouch.server.global.mysql;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static Hampouch.server.global.mysql.MySqlContainerConfig.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Flyway 마이그레이션이 실 MySQL에 현재 엔티티 스키마를 만들고 Hibernate validate를 통과하는지 확인한다. */
@MySqlContainerTest
class MySqlSchemaCreationTest {

    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("운영과 같은 버전·문자 설정의 MySQL 컨테이너에 붙는다")
    void connectsToRealMySql() {
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        String version = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductVersion());
        String characterSet = jdbc.queryForObject("select @@character_set_server", String.class);
        String collation = jdbc.queryForObject("select @@collation_server", String.class);

        assertThat(product).isEqualTo("MySQL");
        assertThat(version).startsWith(MYSQL_IMAGE.substring(MYSQL_IMAGE.indexOf(':') + 1));
        assertThat(characterSet).isEqualTo(MYSQL_CHARACTER_SET);
        assertThat(collation).isEqualTo(MYSQL_COLLATION);
    }

    @Test
    @DisplayName("Testcontainers의 DB 설정이 운영 Compose 선언과 일치한다")
    void matchesProductionCompose() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.prod.yml"));

        assertThat(compose).contains(
                "image: " + MYSQL_IMAGE,
                "--character-set-server=" + MYSQL_CHARACTER_SET,
                "--collation-server=" + MYSQL_COLLATION,
                "serverTimezone=" + JDBC_TIME_ZONE,
                "characterEncoding=" + JDBC_CHARACTER_ENCODING);
    }

    @Test
    @DisplayName("Flyway 마이그레이션이 엔티티 매핑의 모든 테이블을 만든다")
    void everyMappedTableIsCreated() {
        Set<String> mapped = mappedTableNames();
        Set<String> created = createdTableNames();
        Set<String> missing = new TreeSet<>(mapped);
        missing.removeAll(created);

        // 매핑을 못 읽으면 기대 집합이 비어 아래 검사가 무조건 통과한다
        assertThat(mapped).as("엔티티 매핑에서 읽은 테이블 이름").isNotEmpty();
        assertThat(missing).as("Flyway 마이그레이션에 누락된 테이블").isEmpty();
    }

    @Test
    @DisplayName("빈 MySQL에는 baseline이 아니라 V1 SQL 마이그레이션이 실행된다")
    void appliesVersionOneMigration() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '1' and type = 'SQL' and success = 1
                """, Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V5가 챌린지 비활성 시작일과 챌린지·휴식의 과거 날짜 조회용 인덱스를 생성한다")
    void appliesHistoricalHomeLookupMigration() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '5' and type = 'SQL' and success = 1
                """, Integer.class);
        String challengeIndexColumns = jdbc.queryForObject("""
                select group_concat(column_name order by seq_in_index separator ',')
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'challenge'
                  and index_name = 'idx_challenge_user_date_lookup'
                """, String.class);
        String inactiveFromDefinition = jdbc.queryForObject("""
                select concat(data_type, ':', is_nullable)
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'challenge'
                  and column_name = 'inactive_from'
                """, String.class);
        String restIndexColumns = jdbc.queryForObject("""
                select group_concat(column_name order by seq_in_index separator ',')
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'user_rest'
                  and index_name = 'idx_user_rest_user_date_lookup'
                """, String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(challengeIndexColumns).isEqualTo("user_id,start_date,id,end_date,inactive_from");
        assertThat(inactiveFromDefinition).isEqualTo("date:YES");
        assertThat(restIndexColumns).isEqualTo("user_id,rest_start_date,actual_resume_date");
    }

    @Test
    @DisplayName("기존 스키마는 V1을 재실행하지 않고 version 1로 baseline한다")
    void baselinesExistingSchema() {
        String historyTable = "flyway_baseline_probe_history";

        try {
            MigrateResult result = Flyway.configure()
                    .dataSource(jdbc.getDataSource())
                    .table(historyTable)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .target("1")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            Integer baselined = jdbc.queryForObject("""
                    select count(*)
                    from flyway_baseline_probe_history
                    where version = '1' and type = 'BASELINE' and success = 1
                    """, Integer.class);

            assertThat(result.migrationsExecuted).isZero();
            assertThat(baselined).isEqualTo(1);
        } finally {
            jdbc.execute("drop table if exists " + historyTable);
        }
    }

    private Set<String> mappedTableNames() {
        Set<String> names = new TreeSet<>();
        entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel()
                .forEachEntityDescriptor(entity -> entity.forEachTableDetails(
                        table -> names.add(table.getTableName().toLowerCase(Locale.ROOT))));
        return names;
    }

    private Set<String> createdTableNames() {
        // 컨테이너가 만든 DB 하나만 본다 — information_schema에는 mysql·sys 같은 시스템 스키마의 테이블도 들어 있다
        List<String> names = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()", String.class);
        Set<String> lowerCased = new TreeSet<>();
        names.forEach(name -> lowerCased.add(name.toLowerCase(Locale.ROOT)));
        return lowerCased;
    }
}
