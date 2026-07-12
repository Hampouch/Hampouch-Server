package Hampouch.server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 날짜 계산 기준 시계. Asia/Seoul 고정 — 테스트에서는 Clock.fixed(...)로 교체.
 */
@Configuration
public class ClockConfig {

    // Clock은 자바 표준 클래스라 @Component 자동 스캔이 안 됨 → @Bean으로 수동 등록.
    // 이 반환 객체가 빈이 되어 서비스에 주입됨(테스트는 Clock.fixed로 교체 주입).
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
