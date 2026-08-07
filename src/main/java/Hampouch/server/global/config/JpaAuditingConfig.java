package Hampouch.server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * JPA Auditing — @CreatedDate(생성 시각)를 주입된 Clock(Asia/Seoul) 기준으로 채운다.
 * 엔티티가 직접 now()를 부르지 않게 해 날짜를 Clock으로 일원화(테스트 결정성 + 타임존 일관성).
 */
@Configuration
// @EnableJpaAuditing: Auditing 기능 켜기(기본 꺼짐). dateTimeProviderRef = 시각을 물어볼 빈 이름
// → 기본 시스템시계 대신 아래 @Bean(같은 이름)을 쓰게 해서 createdAt도 우리 Clock 기준으로 통일
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    // 이 빈 이름(auditingDateTimeProvider)이 위 dateTimeProviderRef와 연결됨. 주입된 Clock(서울)으로 현재 시각 공급
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
