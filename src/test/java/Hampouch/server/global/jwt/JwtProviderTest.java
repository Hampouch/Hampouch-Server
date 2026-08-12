package Hampouch.server.global.jwt;

import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-test-minimum-32-bytes";
    private static final long ACCESS_EXP = 3_600_000L;
    private static final long REFRESH_EXP = 1_209_600_000L;

    Instant baseInstant;
    JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        // JJWT의 만료 검증은 parseClaims()에 주입한 Clock을 기준으로 하므로,
        // 발급/검증 모두 이 Clock을 공유하는 한 임의의 시각으로 고정해도 안전하다.
        baseInstant = Instant.now();
        Clock clock = Clock.fixed(baseInstant, ZoneId.of("Asia/Seoul"));
        jwtProvider = new JwtProvider(SECRET, ACCESS_EXP, REFRESH_EXP, clock);
    }

    private JwtProvider providerAt(Instant instant) {
        return new JwtProvider(SECRET, ACCESS_EXP, REFRESH_EXP, Clock.fixed(instant, ZoneId.of("Asia/Seoul")));
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

    @Test
    void getUserIdFromAccessToken_refresh_token을_넣으면_UNAUTHORIZED_예외() {
        String refreshToken = jwtProvider.createRefreshToken(1L);

        assertThatThrownBy(() -> jwtProvider.getUserIdFromAccessToken(refreshToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void getRoleFromAccessToken_위조된_토큰이면_예외로_처리된다() {
        // 과거엔 이 메서드에 try/catch가 없어서 JwtException이 그대로 전파됐다.
        // 지금은 parseAndValidate()를 통하므로 CustomException으로 변환돼야 한다.
        assertThatThrownBy(() -> jwtProvider.getRoleFromAccessToken("not-a-valid-jwt"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void getRoleFromAccessToken_만료된_토큰이면_예외로_처리된다() {
        String accessToken = jwtProvider.createAccessToken(1L, UserRole.USER);
        JwtProvider laterProvider = providerAt(baseInstant.plusMillis(ACCESS_EXP + 1_000L));

        assertThatThrownBy(() -> laterProvider.getRoleFromAccessToken(accessToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void parseAccessToken_userId와_role을_함께_추출한다() {
        String token = jwtProvider.createAccessToken(1L, UserRole.USER);

        JwtProvider.AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(1L);
        assertThat(claims.role()).isEqualTo(UserRole.USER);
    }

    @Test
    void parseAccessToken_refresh_token을_넣으면_UNAUTHORIZED_예외() {
        String refreshToken = jwtProvider.createRefreshToken(1L);

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(refreshToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void getUserIdFromRefreshToken_access_token을_넣으면_INVALID_예외() {
        String accessToken = jwtProvider.createAccessToken(1L, UserRole.USER);

        assertThatThrownBy(() -> jwtProvider.getUserIdFromRefreshToken(accessToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    void getUserIdFromRefreshToken_만료된_refresh_token이면_EXPIRED_예외() {
        String refreshToken = jwtProvider.createRefreshToken(1L);
        JwtProvider laterProvider = providerAt(baseInstant.plusMillis(REFRESH_EXP + 1_000L));

        assertThatThrownBy(() -> laterProvider.getUserIdFromRefreshToken(refreshToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
    }

    @Test
    void getUserIdFromRefreshToken_만료된_access_token이면_EXPIRED가_아닌_INVALID_예외() {
        // 리뷰 지적 사항: 만료된 토큰은 parseClaims()에서 type 검사 이전에
        // ExpiredJwtException부터 발생하므로, type을 확인하지 않으면
        // access token이 만료된 경우에도 EXPIRED로 오분류될 수 있다.
        String accessToken = jwtProvider.createAccessToken(1L, UserRole.USER);
        JwtProvider laterProvider = providerAt(baseInstant.plusMillis(ACCESS_EXP + 1_000L));

        assertThatThrownBy(() -> laterProvider.getUserIdFromRefreshToken(accessToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }
}