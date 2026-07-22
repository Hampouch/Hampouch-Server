package Hampouch.server.domain.auth.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 22, 12, 0);

    private RefreshToken create(LocalDateTime expiredAt) {
        return RefreshToken.create(1L, "hashed-token-value", expiredAt);
    }

    @Test
    void 만료시각_이전이면_유효하다() {
        RefreshToken token = create(now.plusDays(1));
        assertThat(token.isValid(now)).isTrue();
    }

    @Test
    void 만료시각_지나면_무효다() {
        RefreshToken token = create(now.minusDays(1));
        assertThat(token.isValid(now)).isFalse();
    }

    @Test
    void 폐기되면_만료전이어도_무효다() {
        RefreshToken token = create(now.plusDays(1));
        token.revoke();

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.isValid(now)).isFalse();
    }
}