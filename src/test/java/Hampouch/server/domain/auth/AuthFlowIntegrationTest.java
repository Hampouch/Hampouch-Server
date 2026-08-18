package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.RefreshToken;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.repository.RefreshTokenRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 인증부터 회원가입, 로그인, 닉네임 최초 설정, 소셜 로그인까지
 * 실제 스프링 컨텍스트 + 실제 DB를 거쳐 전 구간이 동작하는지 검증한다.
 * (AuthServiceTest는 Mockito 기반 단위 테스트라 DB 반영 여부까지는 검증하지 않으므로,
 *  스키마/저장 관련 회귀는 이 테스트가 잡아준다.)
 * 외부 I/O(이메일 발송, 소셜 플랫폼 토큰 검증)만 mock 처리하고 나머지는 실제 빈을 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @MockitoBean
    EmailSender emailSender; // 실제 SMTP 발송 대신 mock

    @MockitoBean(name = "googleVerifier")
    SocialTokenVerifier socialTokenVerifier; // GoogleVerifier만 mock 처리 (KakaoVerifier는 실제 빈 유지). 테스트에서 provider="GOOGLE"만 사용하므로 충분함.

    @Test
    void 이메일인증부터_회원가입_로그인_로그아웃까지_전체_흐름이_실제_DB로_동작한다() throws Exception {
        String email = "flow-test@example.com";

        // 1) 이메일 인증번호 발송 - 실제로 DB에 EmailVerification row가 생성됨
        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        EmailVerification verification = emailVerificationRepository
                .findByEmailAndPurpose(email, VerificationPurpose.SIGNUP)
                .orElseThrow();
        String code = verification.getVerificationCode();

        // 2) 이메일 인증 확인 - 실제 저장된 코드로 검증
        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        // 3) 회원가입 - 실제 users 테이블에 저장되는지 확인
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"플로우테스터\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));

        User savedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(savedUser.getNickname()).isEqualTo("플로우테스터");
        assertThat(savedUser.isLocalUser()).isTrue();

        // 4) 로그인 - 실제 비밀번호 해시 비교까지 통과하는지 확인
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn();

        JsonNode loginData = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("data");
        String accessToken = loginData.path("accessToken").asText();
        String refreshToken = loginData.path("refreshToken").asText();

        // 5) 내 정보 조회 - 방금 로그인한 유저가 실제로 조회되는지, 닉네임이 있어 needsNickname=false인지
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("플로우테스터"))
                .andExpect(jsonPath("$.data.needsNickname").value(false));

        // 6) 로그아웃 - 실제 refresh_tokens 테이블의 revoked 상태가 바뀌는지
        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        // 7) 로그아웃된 refresh token으로 재발급 시도 -> 실제로 거부되는지 (DB에 revoked=true가 반영됐는지 증명)
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REVOKED"));

        // 응답 코드만으로는 "DB가 실제로 갱신됐는지"까지는 증명되지 않으므로,
        // 저장된 토큰을 직접 재조회해서 revoked 상태까지 확인한다.
        RefreshToken savedToken = refreshTokenRepository.findByTokenHash(hashToken(refreshToken))
                .orElseThrow();
        assertThat(savedToken.isRevoked()).isTrue();
    }

    @Test
    void 소셜로그인_신규가입후_닉네임_최초설정까지_실제_DB로_동작한다() throws Exception {
        String email = "social-flow-test@example.com";

        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify(any()))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo(email, "google-provider-id"));

        // 1) 소셜 로그인(신규 가입) - 닉네임 없이 유저가 생성되는지
        MvcResult socialLoginResult = mvc.perform(post("/api/auth/social")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"providerToken\":\"dummy-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.needsNickname").value(true))
                .andReturn();

        User createdUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(createdUser.hasNickname()).isFalse();
        assertThat(createdUser.getProvider()).isEqualTo(AuthProvider.GOOGLE);

        JsonNode socialLoginData = objectMapper.readTree(socialLoginResult.getResponse().getContentAsString()).path("data");
        String accessToken = socialLoginData.path("accessToken").asText();

        // 2) 같은 이메일로 재로그인 - isNewUser는 false지만 needsNickname은 여전히 true인지 (재로그인 케이스 회귀 방지)
        mvc.perform(post("/api/auth/social")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"providerToken\":\"dummy-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                .andExpect(jsonPath("$.data.needsNickname").value(true));

        // 3) 닉네임 최초 설정 - 실제 users 테이블에 반영되는지
        mvc.perform(patch("/api/auth/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"소셜테스터\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("소셜테스터"));

        User updatedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(updatedUser.getNickname()).isEqualTo("소셜테스터");
        assertThat(updatedUser.hasNickname()).isTrue();

        // 4) 닉네임 설정 후 /api/auth/me 조회 - needsNickname이 false로 바뀌었는지
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsNickname").value(false));

        // 5) 닉네임 재설정 시도 -> 이미 설정됐으므로 거부되는지 (최초 설정 전용 정책이 DB 상태 기준으로 지켜지는지)
        mvc.perform(patch("/api/auth/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"다른닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_SET"));
    }

    @Test
    void 회원탈퇴하면_실제_DB에_탈퇴_상태와_토큰폐기가_반영된다() throws Exception {
        String email = "delete-flow-test@example.com";

        // 사전 준비: 이메일 인증을 DB에 직접 심어두고 회원가입/로그인은 API로 진행
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"탈퇴테스터\"}"))
                .andExpect(status().isOk());

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // 회원 탈퇴
        mvc.perform(delete("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 실제 DB에 soft delete가 반영됐는지 확인
        User deletedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(deletedUser.isDeleted()).isTrue();

        // 탈퇴한 계정으로 재로그인 시도 -> 실제로 거부되는지
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }

    // AuthService.hashToken()과 동일한 로직 - private이라 재사용 불가하므로 테스트에 동일하게 구현.
    // DB에는 refresh token 원문이 아니라 이 해시값으로 저장되므로, 저장된 row를 조회하려면
    // 같은 방식으로 해시해야 한다.
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 비밀번호재설정에_사용한_인증은_소비되고_기존_refresh_token은_폐기된다() throws Exception {
        String email = "password-reset-flow@example.com";
        User user = userRepository.saveAndFlush(
                User.createLocalUser(email, passwordEncoder.encode("oldPassword1"), "재설정플로우")
        );

        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(
                email, "123456", VerificationPurpose.PASSWORD_RESET, now.plusMinutes(10)
        );
        verification.verify(now);
        emailVerificationRepository.saveAndFlush(verification);

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"oldPassword1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String oldRefreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();
        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenHash(hashToken(oldRefreshToken))
                .orElseThrow();

        mvc.perform(patch("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isOk());

        EmailVerification consumed = emailVerificationRepository
                .findByEmailAndPurpose(email, VerificationPurpose.PASSWORD_RESET)
                .orElseThrow();
        RefreshToken revoked = refreshTokenRepository.findById(refreshToken.getId()).orElseThrow();

        assertThat(consumed.isVerified()).isFalse();
        assertThat(consumed.getVerifiedAt()).isNull();
        assertThat(revoked.isRevoked()).isTrue();

        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"code\":\"123456\",\"purpose\":\"PASSWORD_RESET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_CODE_EXPIRED"));

        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REVOKED"));

        mvc.perform(patch("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"newPassword\":\"anotherPassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_NOT_VERIFIED"));
    }

    @Test
    void refresh_token으로_인증필요_API를_호출하면_거부된다() throws Exception {
        String email = "refresh-as-access-test@example.com";

        // 사전 준비: 이메일 인증을 DB에 직접 심어두고 회원가입/로그인은 API로 진행
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"리프레시테스터\"}"))
                .andExpect(status().isOk());

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        // refresh token을 Authorization 헤더에 넣어 인증 필요 API 호출 -> 거부되어야 함
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void 인증번호를_재발급하면_새로운_행을_만들지_않고_기존_행을_갱신한다() throws Exception {
        String email = "reissue-flow-" + System.currentTimeMillis() + "@example.com";

        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk());

        EmailVerification first = emailVerificationRepository
                .findByEmailAndPurpose(email, VerificationPurpose.SIGNUP)
                .orElseThrow();
        Long verificationId = first.getId();

        LocalDateTime cooldownExpiredAt = first.getExpiredAt().minusSeconds(31);

        jdbc.update(
                "UPDATE email_verifications SET expired_at = ? WHERE verification_id = ?",
                cooldownExpiredAt,
                verificationId
        );
        entityManager.clear();

        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk());

        EmailVerification reissued = emailVerificationRepository
                .findByEmailAndPurpose(email, VerificationPurpose.SIGNUP)
                .orElseThrow();

        assertThat(reissued.getId()).isEqualTo(verificationId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email = ? AND purpose = 'SIGNUP'",
                Long.class, email
        )).isEqualTo(1L);
        verify(emailSender, times(2))
                .send(eq(email), anyString(), eq(VerificationPurpose.SIGNUP));
    }
}
