package Hampouch.server.global.mysql;

import jakarta.persistence.EntityManagerFactory;
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

/**
 * Flyway 마이그레이션이 실 MySQL에 현재 엔티티 스키마를 만들고 Hibernate validate를 통과하는지 확인한다.
 *
 * "기존 스키마를 baseline한 뒤 V2·V3가 실제로 실행되는지" 검증하는 테스트는 별도 스키마
 * 생성 권한이 필요해 {@link MySqlBaselineTransitionTest}로 분리했다 (공용 컨테이너에
 * 영향을 주지 않기 위함).
 */
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
    @DisplayName("V6가 챌린지 날짜 조회 경계와 활성 휴식 조회용 인덱스를 생성한다")
    void appliesHistoricalHomeLookupMigration() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '6' and type = 'SQL' and success = 1
                """, Integer.class);
        String challengeIndexColumns = jdbc.queryForObject("""
                select group_concat(column_name order by seq_in_index separator ',')
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'challenge'
                  and index_name = 'idx_challenge_user_date_lookup'
                """, String.class);
        String restIndexColumns = jdbc.queryForObject("""
                select group_concat(column_name order by seq_in_index separator ',')
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'user_rest'
                  and index_name = 'idx_user_rest_user_date_lookup'
                """, String.class);
        String inactiveFromDefinition = jdbc.queryForObject("""
                select concat(data_type, ':', is_nullable)
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'challenge'
                  and column_name = 'inactive_from'
                """, String.class);
        assertThat(applied).isEqualTo(1);
        assertThat(challengeIndexColumns).isEqualTo("user_id,start_date,id,end_date,inactive_from");
        assertThat(restIndexColumns).isEqualTo("user_id,rest_start_date,actual_resume_date");
        assertThat(inactiveFromDefinition).isEqualTo("date:YES");
    }

    @Test
    @DisplayName("V7이 챌린지 지출 잠금 시각 컬럼을 의미에 맞는 이름으로 변경한다")
    void renamesChallengeExpenseLockTimestamp() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '7' and type = 'SQL' and success = 1
                """, Integer.class);
        Integer expenseLockedAt = jdbc.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'challenge'
                  and column_name = 'expense_locked_at'
                  and data_type = 'datetime'
                  and is_nullable = 'YES'
                """, Integer.class);
        Integer legacyColumnCount = jdbc.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'challenge'
                  and column_name = 'closed_at'
                """, Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(expenseLockedAt).isEqualTo(1);
        assertThat(legacyColumnCount).isZero();
    }

    @Test
    @DisplayName("V8이 정기 확정 대상을 진행 상태와 ID 순서로 조회하는 인덱스를 생성한다")
    void addsChallengeFinalizationScanIndex() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '8' and type = 'SQL' and success = 1
                """, Integer.class);
        String indexColumns = jdbc.queryForObject("""
                select group_concat(column_name order by seq_in_index separator ',')
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'challenge'
                  and index_name = 'idx_challenge_status_id'
                """, String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(indexColumns).isEqualTo("status,id");
    }

    @Test
    @DisplayName("V9가 커뮤니티 테이블과 조회용 제약 및 인덱스를 생성한다")
    void createsCommunitySchema() {
        Integer applied = jdbc.queryForObject("""
            select count(*)
            from flyway_schema_history
            where version = '9'
              and type = 'SQL'
              and success = 1
            """, Integer.class);

        Integer communityTableCount = jdbc.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = database()
              and table_name in (
                  'post',
                  'food_post_detail',
                  'recruit_post_detail',
                  'post_image',
                  'post_like',
                  'post_bookmark',
                  'post_comment'
              )
            """, Integer.class);

        List<String> uniqueConstraints = jdbc.queryForList("""
            select constraint_name
            from information_schema.table_constraints
            where table_schema = database()
              and constraint_type = 'UNIQUE'
              and constraint_name in (
                  'uk_post_like_post_user',
                  'uk_post_bookmark_post_user',
                  'uk_post_image_post_sort'
              )
            """, String.class);

        String postIndexColumns = jdbc.queryForObject("""
            select group_concat(
                column_name
                order by seq_in_index
                separator ','
            )
            from information_schema.statistics
            where table_schema = database()
              and table_name = 'post'
              and index_name = 'idx_post_category_created'
            """, String.class);

        String topLevelCommentIndexColumns = jdbc.queryForObject("""
            select group_concat(
                column_name
                order by seq_in_index
                separator ','
            )
            from information_schema.statistics
            where table_schema = database()
              and table_name = 'post_comment'
              and index_name = 'idx_post_comment_top_level'
            """, String.class);

        String replyIndexColumns = jdbc.queryForObject("""
            select group_concat(
                column_name
                order by seq_in_index
                separator ','
            )
            from information_schema.statistics
            where table_schema = database()
              and table_name = 'post_comment'
              and index_name = 'idx_post_comment_parent'
            """, String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(communityTableCount).isEqualTo(7);
        assertThat(uniqueConstraints).containsExactlyInAnyOrder(
                "uk_post_like_post_user",
                "uk_post_bookmark_post_user",
                "uk_post_image_post_sort"
        );
        assertThat(postIndexColumns)
                .isEqualTo("category,created_at,post_id");
        assertThat(topLevelCommentIndexColumns)
                .isEqualTo("post_id,parent_comment_id,created_at,comment_id");
        assertThat(replyIndexColumns)
                .isEqualTo("parent_comment_id,created_at,comment_id");
    }

    @DisplayName("Flyway V4가 목표 조정 DB ENUM에 PLUS_30을 추가한다")
    void appliesPlusThirtyAdjustmentOptionMigration() {
        Integer applied = jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '4' and type = 'SQL' and success = 1
                """, Integer.class);
        String columnType = jdbc.queryForObject("""
                select column_type
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'challenge_adjustment'
                  and column_name = 'adjust_option'
                """, String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(columnType).isEqualTo("enum('PLUS_10','PLUS_20','PLUS_30')");
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
