package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.dto.request.LoginRequest;
import Hampouch.server.domain.auth.dto.request.NicknameSetRequest;
import Hampouch.server.domain.auth.dto.request.PasswordResetRequest;
import Hampouch.server.domain.auth.dto.request.RefreshRequest;
import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.service.AuthService;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #182: 회원 탈퇴(deleteMe)를 경계로 기존 사용자 인증·계정 변경 경로 전체가 사용자 행 락
 * (UserOperationLock, #177)으로 직렬화되는지 검증한다.
 *
 * raw JDBC로 users 행에 직접 SELECT ... FOR UPDATE를 걸어 붙잡아둔 채로 실제 서비스
 * 메서드 두 개를 동시에 호출해, 진짜로 DB 레벨에서 경쟁하는지(둘 다 우리가 풀어줄 때까지
 * 완료되지 않는지)를 먼저 확인한다. 어느 쪽이 먼저 락을 얻는지는 일부러 강제하지 않는다 —
 * 강제하면 "락이 실제로 겹친다"는, 이 테스트가 검증하려는 사실 자체를 스스로 무너뜨리게
 * 된다. 대신 결과가 두 가지 합법적 순서 중 하나와 반드시 일치하는지를 검증한다.
 *
 * 컨트롤러 라우팅에 의존하지 않기 위해 MockMvc 대신 AuthService 빈을 직접 호출한다
 * (deleteMe/setInitialNickname은 이미 userId를 인자로 직접 받는 구조라 더 정확하다).
 */
@MySqlContainerTest
class AuthUserLockConcurrencyTest {

    @Autowired
    AuthService authService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    EmailSender emailSender;

    @MockitoBean(name = "googleVerifier")
    SocialTokenVerifier socialTokenVerifier;

    private record Outcome(boolean success, Exception error) {
    }

    @Test
    void 탈퇴와_재발급이_경쟁하면_한쪽_순서로만_처리되고_최종_활성_토큰은_남지_않는다() throws Exception {
        Long userId = createLocalUser("lock-refresh-" + System.currentTimeMillis() + "@example.com", "닉네임A");
        String refreshToken = authService.login(new LoginRequest(email(userId), "password1")).refreshToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            lockUserRow(holderConn, userId);

            Future<Outcome> deleteCall = executor.submit(() -> callDeleteMe(userId));
            Future<Outcome> refreshCall = executor.submit(() -> callReissue(refreshToken));

            Thread.sleep(500);
            assertThat(deleteCall.isDone()).as("탈퇴 요청은 사용자 행 락에 막혀 대기 중이어야 한다").isFalse();
            assertThat(refreshCall.isDone()).as("재발급 요청도 사용자 행 락에 막혀 대기 중이어야 한다").isFalse();

            holderConn.rollback();

            Outcome deleteOutcome = deleteCall.get(10, TimeUnit.SECONDS);
            Outcome refreshOutcome = refreshCall.get(10, TimeUnit.SECONDS);

            // deleteMe는 경쟁 상대가 무엇이든 상관없이(먼저 가든 나중에 가든) 항상 성공한다 -
            // 활성 유저 하나를 지우는 것뿐이라 순서를 구분해주는 신호가 되지 못한다.
            // 순서를 실제로 구분하는 신호는 refreshOutcome이다: 성공했으면 재발급이 먼저 간 것이고,
            // AUTH_REFRESH_TOKEN_REVOKED로 실패했으면 탈퇴가 먼저 커밋된 것이다.
            assertThat(deleteOutcome.success()).as("탈퇴는 어느 순서든 성공해야 한다").isTrue();

            if (!refreshOutcome.success()) {
                assertThat(refreshOutcome.error()).isInstanceOf(CustomException.class);
                assertThat(((CustomException) refreshOutcome.error()).getErrorCode())
                        .as("탈퇴가 먼저 커밋됐다면 재발급은 REVOKED로 실패해야 한다")
                        .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_REVOKED);
            }

            // 재발급이 먼저 성공해 새 토큰을 만들었더라도, 그 뒤 실행되는 탈퇴의
            // revokeAllByUserId가 그 시점의 모든 활성 토큰을 지우므로 순서와 무관하게
            // 최종 활성 토큰은 항상 0이어야 한다.
            Integer activeTokenCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false",
                    Integer.class, userId);
            assertThat(activeTokenCount).as("최종 활성 토큰은 순서와 무관하게 0이어야 한다").isEqualTo(0);
            assertThat(isDeleted(userId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 탈퇴와_로그인이_경쟁하면_한쪽_순서로만_처리되고_최종_활성_토큰은_남지_않는다() throws Exception {
        String email = "lock-login-" + System.currentTimeMillis() + "@example.com";
        Long userId = createLocalUser(email, "닉네임B");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            lockUserRow(holderConn, userId);

            Future<Outcome> deleteCall = executor.submit(() -> callDeleteMe(userId));
            Future<Outcome> loginCall = executor.submit(() -> callLogin(email));

            Thread.sleep(500);
            assertThat(deleteCall.isDone()).isFalse();
            assertThat(loginCall.isDone()).isFalse();

            holderConn.rollback();

            Outcome deleteOutcome = deleteCall.get(10, TimeUnit.SECONDS);
            Outcome loginOutcome = loginCall.get(10, TimeUnit.SECONDS);

            // deleteMe는 순서와 무관하게 항상 성공한다 - 순서를 구분하는 신호는 loginOutcome이다.
            assertThat(deleteOutcome.success()).as("탈퇴는 어느 순서든 성공해야 한다").isTrue();

            if (!loginOutcome.success()) {
                assertThat(loginOutcome.error()).isInstanceOf(CustomException.class);
                assertThat(((CustomException) loginOutcome.error()).getErrorCode())
                        .as("탈퇴가 먼저 커밋됐다면 로그인은 USER_DELETED로 실패해야 한다")
                        .isEqualTo(UserErrorCode.USER_DELETED);
            }

            Integer activeTokenCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false",
                    Integer.class, userId);
            assertThat(activeTokenCount).as("최종 활성 토큰은 순서와 무관하게 0이어야 한다").isEqualTo(0);
            assertThat(isDeleted(userId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 탈퇴와_비밀번호재설정이_경쟁하면_한쪽_순서로만_처리된다() throws Exception {
        String email = "lock-reset-" + System.currentTimeMillis() + "@example.com";
        Long userId = createLocalUser(email, "닉네임C");
        prepareVerifiedPasswordReset(email);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            lockUserRow(holderConn, userId);

            Future<Outcome> deleteCall = executor.submit(() -> callDeleteMe(userId));
            Future<Outcome> resetCall = executor.submit(() -> callResetPassword(email));

            Thread.sleep(500);
            assertThat(deleteCall.isDone()).isFalse();
            assertThat(resetCall.isDone()).isFalse();

            holderConn.rollback();

            Outcome deleteOutcome = deleteCall.get(10, TimeUnit.SECONDS);
            Outcome resetOutcome = resetCall.get(10, TimeUnit.SECONDS);

            assertThat(deleteOutcome.success()).as("탈퇴는 어느 순서든 성공해야 한다").isTrue();
            if (!resetOutcome.success()) {
                assertThat(resetOutcome.error()).isInstanceOf(CustomException.class);
                assertThat(((CustomException) resetOutcome.error()).getErrorCode())
                        .as("탈퇴가 먼저 커밋됐다면 비밀번호 재설정은 USER_DELETED로 실패해야 한다")
                        .isEqualTo(UserErrorCode.USER_DELETED);
            }
            assertThat(isDeleted(userId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 탈퇴와_최초닉네임설정이_경쟁하면_한쪽_순서로만_처리된다() throws Exception {
        Long userId = createSocialUserWithoutNickname(
                "lock-nickname-" + System.currentTimeMillis() + "@example.com");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            lockUserRow(holderConn, userId);

            Future<Outcome> deleteCall = executor.submit(() -> callDeleteMe(userId));
            Future<Outcome> nicknameCall = executor.submit(() -> callSetInitialNickname(userId, "새닉네임"));

            Thread.sleep(500);
            assertThat(deleteCall.isDone()).isFalse();
            assertThat(nicknameCall.isDone()).isFalse();

            holderConn.rollback();

            Outcome deleteOutcome = deleteCall.get(10, TimeUnit.SECONDS);
            Outcome nicknameOutcome = nicknameCall.get(10, TimeUnit.SECONDS);

            assertThat(deleteOutcome.success()).as("탈퇴는 어느 순서든 성공해야 한다").isTrue();
            if (!nicknameOutcome.success()) {
                assertThat(nicknameOutcome.error()).isInstanceOf(CustomException.class);
                assertThat(((CustomException) nicknameOutcome.error()).getErrorCode())
                        .as("탈퇴가 먼저 커밋됐다면 최초 닉네임 설정은 USER_DELETED로 실패해야 한다")
                        .isEqualTo(UserErrorCode.USER_DELETED);
            }
            assertThat(isDeleted(userId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 동시에_탈퇴하면_정확히_하나만_성공하고_최종_상태는_DELETED로_한번만_반영된다() throws Exception {
        Long userId = createLocalUser("lock-double-delete-" + System.currentTimeMillis() + "@example.com", "닉네임D");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> callDeleteMe(userId));
            Future<Outcome> second = executor.submit(() -> callDeleteMe(userId));

            Outcome firstOutcome = first.get(10, TimeUnit.SECONDS);
            Outcome secondOutcome = second.get(10, TimeUnit.SECONDS);

            long successCount = Stream.of(firstOutcome, secondOutcome).filter(Outcome::success).count();
            assertThat(successCount).as("동시 탈퇴 두 건 중 정확히 하나만 성공해야 한다").isEqualTo(1);
            assertThat(isDeleted(userId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    // ===================== 헬퍼 =====================

    private void lockUserRow(Connection conn, Long userId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE user_id = ? FOR UPDATE")) {
            ps.setLong(1, userId);
            ps.executeQuery();
        }
    }

    private boolean isDeleted(Long userId) {
        String status = jdbc.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, userId);
        return "DELETED".equals(status);
    }

    private String email(Long userId) {
        return userRepository.findById(userId).orElseThrow().getEmail();
    }

    private Outcome callDeleteMe(Long userId) {
        try {
            authService.deleteMe(userId);
            return new Outcome(true, null);
        } catch (Exception e) {
            return new Outcome(false, e);
        }
    }

    private Outcome callReissue(String refreshToken) {
        try {
            authService.reissueToken(new RefreshRequest(refreshToken));
            return new Outcome(true, null);
        } catch (Exception e) {
            return new Outcome(false, e);
        }
    }

    private Outcome callLogin(String email) {
        try {
            authService.login(new LoginRequest(email, "password1"));
            return new Outcome(true, null);
        } catch (Exception e) {
            return new Outcome(false, e);
        }
    }

    private Outcome callResetPassword(String email) {
        try {
            authService.resetPassword(new PasswordResetRequest(email, "newPassword1"));
            return new Outcome(true, null);
        } catch (Exception e) {
            return new Outcome(false, e);
        }
    }

    private Outcome callSetInitialNickname(Long userId, String nickname) {
        try {
            authService.setInitialNickname(userId, new NicknameSetRequest(nickname));
            return new Outcome(true, null);
        } catch (Exception e) {
            return new Outcome(false, e);
        }
    }

    private Long createLocalUser(String email, String nickname) {
        User user = User.createLocalUser(email, passwordEncoder.encode("password1"), nickname);
        return userRepository.save(user).getId();
    }

    private Long createSocialUserWithoutNickname(String email) {
        User user = User.createSocialUser(email, AuthProvider.GOOGLE, "google-" + System.currentTimeMillis());
        return userRepository.save(user).getId();
    }

    private void prepareVerifiedPasswordReset(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.PASSWORD_RESET, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }
}