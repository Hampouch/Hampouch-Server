package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import Hampouch.server.global.jwt.JwtProvider;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동시 요청 상황에서의 경쟁 상태(race condition)를 검증한다.
 *
 * 리포지토리 메서드를 Mockito 스파이로 감싸 타이밍을 제어하는 방식을 시도했으나, 이
 * 환경에서 스파이가 Spring Data 리포지토리 프록시 호출을 가로채지 못해(원인 미상) 계속
 * 실패했다. 그래서 애플리케이션/스프링 내부 동작에 전혀 의존하지 않는 방식으로 다시
 * 짰다: raw JDBC로 별도 커넥션을 열어 트랜잭션을 커밋하지 않은 채로 직접 행을 잠그거나
 * 삽입해서 붙잡아두고, 그 사이 실제 HTTP 요청이 MySQL 서버 자체의 락/유니크 제약에
 * 진짜로 걸리는지를 검증한다. 이 방식은 Mockito나 Spring의 내부 프록시 동작에 의존하지
 * 않고 순수하게 MySQL의 실제 동작만으로 성립한다.
 */
@MySqlContainerTest
@AutoConfigureMockMvc
class AuthConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    EmailSender emailSender;

    @MockitoBean(name = "googleVerifier")
    SocialTokenVerifier socialTokenVerifier;

    private record HttpResult(int status, String body) {
    }

    // ===================== refresh token 재발급 =====================

    @Test
    void 동시에_같은_refreshToken으로_재발급하면_하나만_성공하고_기존_토큰은_폐기된다() throws Exception {
        String email = "concurrent-refresh-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);
        signup(email, "재발급테스터");
        String refreshToken = login(email);
        String tokenHash = hashToken(refreshToken);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);

            // raw JDBC로 직접 SELECT ... FOR UPDATE를 걸어 토큰 행을 붙잡아둔다.
            // AuthService가 쓰는 findByTokenHashForUpdate와 동일한 잠금(PESSIMISTIC_WRITE)을
            // 애플리케이션 코드를 거치지 않고 재현한 것이다.
            try (PreparedStatement ps = holderConn.prepareStatement(
                    "SELECT * FROM refresh_tokens WHERE token_hash = ? FOR UPDATE")) {
                ps.setString(1, tokenHash);
                ps.executeQuery();
            }

            Future<HttpResult> firstCall = executor.submit(() -> callRefresh(refreshToken));
            Future<HttpResult> secondCall = executor.submit(() -> callRefresh(refreshToken));

            // 두 실제 요청 모두 같은 행에 대한 SELECT ... FOR UPDATE에서 우리가 쥔 락에
            // 진짜로 막혀야 한다. 짧게 대기한 뒤에도 둘 다 완료되지 않았어야 한다.
            Thread.sleep(500);
            assertThat(firstCall.isDone())
                    .as("첫 번째 요청은 raw JDBC 락에 막혀 아직 완료되면 안 된다")
                    .isFalse();
            assertThat(secondCall.isDone())
                    .as("두 번째 요청도 raw JDBC 락에 막혀 아직 완료되면 안 된다")
                    .isFalse();

            // 락을 풀어준다 (우리는 읽기만 했으므로 커밋/롤백 어느 쪽이든 무방하다).
            holderConn.rollback();

            HttpResult first = firstCall.get(10, TimeUnit.SECONDS);
            HttpResult second = secondCall.get(10, TimeUnit.SECONDS);

            long successCount = countStatus(first, second, 200);
            long revokedCount = countStatus(first, second, 401);
            assertThat(successCount).as("정확히 하나만 200이어야 한다").isEqualTo(1);
            assertThat(revokedCount).as("정확히 하나만 401이어야 한다").isEqualTo(1);

            HttpResult failed = first.status() == 401 ? first : second;
            assertThat(failed.body()).contains("AUTH_REFRESH_TOKEN_REVOKED");

            Integer revokedRowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND revoked = true",
                    Integer.class, tokenHash);
            assertThat(revokedRowCount).as("원래 토큰은 폐기 상태로 남아야 한다").isEqualTo(1);

            // TokenReissueResponse에는 user 정보가 없다 (LoginResponse와 달리 accessToken/
            // refreshToken/만료시간만 담김) - 응답 본문에서 userId를 꺼내려 하면 항상 비어서
            // 아래 조회가 0건이 된다. 대신 테스트에서 이미 알고 있는 email로 직접 조회한다.
            Long userId = jdbc.queryForObject(
                    "SELECT user_id FROM users WHERE email = ?", Long.class, email);
            Integer activeTokenCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false",
                    Integer.class, userId);
            assertThat(activeTokenCount).as("새로 발급된 활성 토큰이 정확히 하나만 있어야 한다").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 한_토큰의_락은_다른_토큰의_재발급을_막지_않는다() throws Exception {
        String emailA = "concurrent-refresh-a-" + System.currentTimeMillis() + "@example.com";
        String emailB = "concurrent-refresh-b-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(emailA);
        prepareVerifiedEmail(emailB);
        signup(emailA, "락A테스터");
        signup(emailB, "락B테스터");
        String refreshTokenA = login(emailA);
        String refreshTokenB = login(emailB);
        String tokenHashA = hashToken(refreshTokenA);

        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);

            try (PreparedStatement ps = holderConn.prepareStatement(
                    "SELECT * FROM refresh_tokens WHERE token_hash = ? FOR UPDATE")) {
                ps.setString(1, tokenHashA);
                ps.executeQuery();
            }

            // 토큰 A가 잠겨 있는 동안, 무관한 토큰 B의 재발급은 지연 없이 끝나야 한다.
            HttpResult resultB = callRefresh(refreshTokenB);
            assertThat(resultB.status())
                    .as("다른 행(토큰 B)은 토큰 A의 락과 무관하게 즉시 처리되어야 한다")
                    .isEqualTo(200);

            holderConn.rollback();
        }

        HttpResult resultA = callRefresh(refreshTokenA);
        assertThat(resultA.status()).isEqualTo(200);
    }

    @Test
    void 같은_시각에_발급된_refreshToken은_서로_다르고_해시도_다르다() throws NoSuchAlgorithmException {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtProvider fixedClockJwtProvider = new JwtProvider(
                "test-secret-key-for-uniqueness-check-minimum-32-bytes-long",
                3_600_000L,
                1_209_600_000L,
                fixedClock
        );

        String token1 = fixedClockJwtProvider.createRefreshToken(1L);
        String token2 = fixedClockJwtProvider.createRefreshToken(1L);

        assertThat(token1).as("발급 시각이 같아도 jti가 랜덤이라 토큰 문자열은 달라야 한다").isNotEqualTo(token2);
        assertThat(hashToken(token1)).as("토큰이 다르면 해시도 달라야 한다 - 해시 충돌로 인한 중복 저장 방지").isNotEqualTo(hashToken(token2));
    }

    // ===================== 회원가입 이메일/닉네임 중복 =====================

    @Test
    void 동시에_같은_이메일로_회원가입하면_이메일_중복으로_거부되고_한_명만_저장된다() throws Exception {
        String email = "concurrent-signup-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);

        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);

            // raw JDBC로 같은 이메일 유저를 커밋하지 않은 채로 직접 삽입해서 붙잡아둔다.
            // 이 상태에서는 다른 트랜잭션에서 findByEmail로 조회해도 (스냅샷 격리상) 보이지
            // 않으므로, 실제 회원가입 요청은 사전 검사를 통과한 뒤 실제 INSERT 시점에
            // MySQL의 유니크 인덱스 잠금에 의해 막힌다 - "사전 검사를 통과한 두 요청이
            // INSERT를 경쟁"하는 상황을 정확히 재현한다.
            try (PreparedStatement ps = holderConn.prepareStatement(
                    "INSERT INTO users (email, provider, role, status, created_at, updated_at) " +
                            "VALUES (?, 'LOCAL', 'USER', 'ACTIVE', NOW(), NOW())")) {
                ps.setString(1, email);
                ps.executeUpdate();
            }

            Future<HttpResult> call;
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                call = executor.submit(() -> callSignup(email, "동시성유저"));

                Thread.sleep(500);
                assertThat(call.isDone())
                        .as("실제 요청은 우리가 커밋하지 않은 동일 이메일 INSERT와 충돌해 막혀 있어야 한다")
                        .isFalse();

                holderConn.commit();

                HttpResult result = call.get(10, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(409);
                assertThat(result.body()).contains("AUTH_EMAIL_ALREADY_EXISTS");
            } finally {
                executor.shutdownNow();
            }
        }

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(userCount).as("해당 이메일 유저는 정확히 한 명만 저장되어야 한다").isEqualTo(1);
    }

    @Test
    void 동시에_같은_닉네임으로_회원가입하면_닉네임_중복으로_거부되고_한_명만_저장된다() throws Exception {
        String holderEmail = "concurrent-nickname-holder-" + System.currentTimeMillis() + "@example.com";
        String realEmail = "concurrent-nickname-real-" + System.currentTimeMillis() + "@example.com";
        String nickname = "닉" + (System.currentTimeMillis() % 100000);
        prepareVerifiedEmail(realEmail);

        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);

            try (PreparedStatement ps = holderConn.prepareStatement(
                    "INSERT INTO users (email, nickname, provider, role, status, created_at, updated_at) " +
                            "VALUES (?, ?, 'LOCAL', 'USER', 'ACTIVE', NOW(), NOW())")) {
                ps.setString(1, holderEmail);
                ps.setString(2, nickname);
                ps.executeUpdate();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResult> call = executor.submit(() -> callSignup(realEmail, nickname));

                Thread.sleep(500);
                assertThat(call.isDone())
                        .as("실제 요청은 우리가 커밋하지 않은 동일 닉네임 INSERT와 충돌해 막혀 있어야 한다")
                        .isFalse();

                holderConn.commit();

                HttpResult result = call.get(10, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(409);
                assertThat(result.body()).contains("USER_NICKNAME_ALREADY_EXISTS");
            } finally {
                executor.shutdownNow();
            }
        }

        Integer nicknameCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE nickname = ?", Integer.class, nickname);
        assertThat(nicknameCount).as("해당 닉네임 유저는 정확히 한 명만 저장되어야 한다").isEqualTo(1);
    }

    @Test
    void 사전검사에서_닉네임_중복이면_바로_거부된다() throws Exception {
        String email1 = "nickname-check-1-" + System.currentTimeMillis() + "@example.com";
        String email2 = "nickname-check-2-" + System.currentTimeMillis() + "@example.com";
        String duplicateNickname = "중복될닉";

        prepareVerifiedEmail(email1);
        prepareVerifiedEmail(email2);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email1 + "\",\"password\":\"password1\",\"nickname\":\"" + duplicateNickname + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email2 + "\",\"password\":\"password1\",\"nickname\":\"" + duplicateNickname + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_EXISTS"));
    }

    // ===================== 헬퍼 =====================

    private long countStatus(HttpResult a, HttpResult b, int status) {
        return java.util.stream.Stream.of(a, b).filter(r -> r.status() == status).count();
    }

    private HttpResult callRefresh(String refreshToken) {
        try {
            MvcResult result = mvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                    .andReturn();
            return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResult callSignup(String email, String nickname) {
        try {
            MvcResult result = mvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"" + nickname + "\"}"))
                    .andReturn();
            return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void signup(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();
        assertThat(refreshToken)
                .as("로그인 응답에 refreshToken이 반드시 있어야 한다. 응답 본문: %s",
                        loginResult.getResponse().getContentAsString())
                .isNotBlank();
        return refreshToken;
    }

    private void prepareVerifiedEmail(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }

    private String hashToken(String token) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}