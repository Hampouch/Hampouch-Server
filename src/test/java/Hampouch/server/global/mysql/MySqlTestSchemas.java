package Hampouch.server.global.mysql;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 공유 MySQL 컨테이너(하나의 인스턴스) 안에 독립된 스키마(데이터베이스)를 새로 만들어주는
 * 테스트 유틸리티.
 *
 * 기본 스키마(test)는 Spring 컨텍스트 부팅 시점에 이미 V1~최신 버전까지 Flyway가 전부
 * 적용해버린 상태라, "V1만 있는 상태"나 "중복 데이터가 있는 상태" 같은 마이그레이션
 * 중간 단계를 재현할 수 없다. 컨테이너를 새로 띄우지 않고도(비용이 큼) 같은 컨테이너
 * 안에 별도 스키마를 만들어 이런 상태를 격리해서 재현하기 위한 헬퍼다.
 *
 * MySqlContainerConfig의 초기화 스크립트(mysql-test-init.sql)가 컨테이너 부팅 시점에
 * test 계정에 전체 권한을 부여해두므로, 여기서는 별도 root 계정 없이 기본 test 계정
 * 그대로 CREATE/DROP DATABASE 같은 관리자 작업을 수행한다.
 */
final class MySqlTestSchemas {

    private MySqlTestSchemas() {
    }

    record IsolatedSchema(String jdbcUrl, String username, String password, JdbcTemplate jdbc) {
    }

    static IsolatedSchema create(JdbcTemplate baseJdbc, String schemaName) {
        baseJdbc.execute("DROP DATABASE IF EXISTS " + schemaName);
        baseJdbc.execute("CREATE DATABASE " + schemaName);

        HikariDataSource baseDataSource = (HikariDataSource) baseJdbc.getDataSource();
        String username = baseDataSource.getUsername();
        String password = baseDataSource.getPassword();
        // 기본 스키마 이름(test)만 새 스키마 이름으로 바꿔치기해서 같은 컨테이너의 다른 DB를 가리키게 한다.
        String jdbcUrl = baseDataSource.getJdbcUrl().replaceFirst("/test(\\?|$)", "/" + schemaName + "$1");

        DataSource isolatedDataSource = DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();

        return new IsolatedSchema(jdbcUrl, username, password, new JdbcTemplate(isolatedDataSource));
    }

    static void drop(JdbcTemplate baseJdbc, String schemaName) {
        baseJdbc.execute("DROP DATABASE IF EXISTS " + schemaName);
    }
}