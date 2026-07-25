package Hampouch.server.domain.auth.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 22, 12, 0);

    private EmailVerification create(LocalDateTime expiredAt) {
        return EmailVerification.create("test@example.com", "123456", VerificationPurpose.SIGNUP, expiredAt);
    }

    @Test
    void 만료시각_이전이면_만료가_아니다() {
        EmailVerification v = create(now.plusMinutes(1));
        assertThat(v.isExpired(now)).isFalse();
    }

    @Test
    void 만료시각_이후면_만료다() {
        EmailVerification v = create(now.minusMinutes(1));
        assertThat(v.isExpired(now)).isTrue();
    }

    @Test
    void 인증코드가_일치하면_true() {
        EmailVerification v = create(now.plusMinutes(10));
        assertThat(v.isCodeMatch("123456")).isTrue();
    }

    @Test
    void 인증코드가_다르면_false() {
        EmailVerification v = create(now.plusMinutes(10));
        assertThat(v.isCodeMatch("000000")).isFalse();
    }

    @Test
    void verify_호출하면_인증완료_상태와_인증시각이_기록된다() {
        EmailVerification v = create(now.plusMinutes(10));
        v.verify(now);

        assertThat(v.isVerified()).isTrue();
        assertThat(v.getVerifiedAt()).isEqualTo(now);
    }

    @Test
    void 인증안된_상태는_유효시간_판단시_만료로_취급된다() {
        EmailVerification v = create(now.plusMinutes(10));
        // verify()를 아직 호출 안 함 -> verifiedAt == null
        assertThat(v.isVerificationExpired(now)).isTrue();
    }

    @Test
    void 인증후_1시간_이내면_유효하다() {
        EmailVerification v = create(now.plusMinutes(10));
        v.verify(now);

        assertThat(v.isVerificationExpired(now.plusMinutes(59))).isFalse();
    }

    @Test
    void 인증후_1시간_지나면_만료다() {
        EmailVerification v = create(now.plusMinutes(10));
        v.verify(now);

        assertThat(v.isVerificationExpired(now.plusHours(1).plusSeconds(1))).isTrue();
    }

    @Test
    void 시도횟수가_5회_미만이면_초과가_아니다() {
        EmailVerification v = create(now.plusMinutes(10));
        for (int i = 0; i < 4; i++) {
            v.increaseAttempt();
        }
        assertThat(v.isAttemptExceeded()).isFalse();
    }

    @Test
    void 시도횟수가_5회면_초과다() {
        EmailVerification v = create(now.plusMinutes(10));
        for (int i = 0; i < 5; i++) {
            v.increaseAttempt();
        }
        assertThat(v.isAttemptExceeded()).isTrue();
    }
}