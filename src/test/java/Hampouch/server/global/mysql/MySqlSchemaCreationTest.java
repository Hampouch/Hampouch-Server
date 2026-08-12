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
 * 엔티티 매핑이 실 MySQL에서 실제로 테이블이 되는지 확인한다.
 * ddl-auto는 CREATE TABLE이 거부돼도 경고만 남기고 부팅을 계속하므로 "컨텍스트가 떴다"로는 판정이 안 되고,
 * H2는 MySQL이 거부하는 예약어 컬럼을 통과시켜 기존 테스트가 전건 초록인 채로 배포까지 간 적이 있다.
 */
@MySqlContainerTest
class MySqlSchemaCreationTest {

    /**
     * 예약어 컬럼 때문에 실 MySQL이 아직 만들지 못하는 테이블 — 원인이 고쳐지면 이 목록에서 뺀다.
     * challenge_adjustment는 option 컬럼(수정본이 리뷰 대기), battle_participant는 rank 컬럼(배틀 담당)이다.
     * 목록에 없는 테이블이 빠지면 새로 생긴 결함이므로 이 테스트가 실패한다.
     */
    private static final Set<String> KNOWN_MISSING_TABLES = Set.of("challenge_adjustment", "battle_participant");

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
    @DisplayName("엔티티 매핑에 있는 테이블이 실 MySQL에도 전부 만들어진다")
    void everyMappedTableIsCreated() {
        Set<String> mapped = mappedTableNames();
        Set<String> created = createdTableNames();
        Set<String> missing = new TreeSet<>(mapped);
        missing.removeAll(created);

        // 매핑을 못 읽으면 기대 집합이 비어 아래 검사가 무조건 통과한다
        assertThat(mapped).as("엔티티 매핑에서 읽은 테이블 이름").isNotEmpty();
        assertThat(missing).as("실 MySQL이 생성을 거부한 테이블").isSubsetOf(KNOWN_MISSING_TABLES);
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
