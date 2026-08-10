package Hampouch.server.global.jwt;

import Hampouch.server.domain.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        // JJWT 라이브러리의 만료 검증은 실제 시스템 시각을 기준으로 하므로,
        // Clock을 임의의 과거/미래로 고정하면 발급 시점과 검증 시점의 기준이 어긋나
        // "발급하자마자 만료됨" 같은 오류가 난다. 실제 현재 시각(Instant.now())을
        // 기준으로 고정해야 발급/검증이 같은 시간대에서 일관되게 동작한다.
        Clock clock = Clock.fixed(Instant.now(), ZoneId.of("Asia/Seoul"));
        jwtProvider = new JwtProvider(
                "test-secret-key-for-jwt-provider-test-minimum-32-bytes",
                3_600_000L,
                1_209_600_000L,
                clock
        );
    }

    @Test
    void isAccessToken_access_token이면_true() {
        String token = jwtProvider.createAccessToken(1L, UserRole.USER);

        assertThat(jwtProvider.isAccessToken(token)).isTrue();
    }

    @Test
    void isAccessToken_refresh_token이면_false() {
        String token = jwtProvider.createRefreshToken(1L);

        assertThat(jwtProvider.isAccessToken(token)).isFalse();
    }

    @Test
    void isAccessToken_위조된_토큰이면_false() {
        assertThat(jwtProvider.isAccessToken("not-a-valid-jwt")).isFalse();
    }
}