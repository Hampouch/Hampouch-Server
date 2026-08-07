package Hampouch.server.domain.minichallenge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * 추천 목록 무작위 노출(0630 확정)용 난수원 빈.
 *
 * 서비스가 new Random()을 직접 만들지 않고 빈으로 주입받게 분리한 이유는 본챌 #1의 Clock 빈과
 * 동일 — 테스트에서 시드 고정 Random으로 갈아끼우면 섞인 순서가 재현돼 검증할 수 있다.
 *
 * 구현체를 RandomGenerator.getDefault()가 아닌 java.util.Random으로 고른 것은 자체 결정 —
 * getDefault()가 주는 기본 구현(L32X64MixRandom 계열)은 스레드 안전이 보장되지 않는데,
 * 이 빈은 싱글턴이라 여러 요청 스레드가 동시에 쓴다. Random은 스레드 안전.
 *
 * 위치를 global/config(#1의 ClockConfig가 있는 곳)가 아닌 도메인 하위에 둔 것도 자체 결정 —
 * 이 빈은 추천 랜덤 노출 전용이라 쓰는 기능 옆에 두고, 병렬 진행 중인 다른 브랜치와의 충돌 면도
 * 줄인다. 팀 리뷰에서 #1 관례대로 global/config 통일로 결정되면 파일만 옮기면 된다.
 */
@Configuration
public class RandomGeneratorConfig {

    @Bean
    public RandomGenerator randomGenerator() {
        return new Random();
    }
}
