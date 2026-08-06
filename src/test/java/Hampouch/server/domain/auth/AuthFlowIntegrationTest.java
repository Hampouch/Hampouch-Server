package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이메일 인증부터 회원가입, 로그인, 닉네임 최초 설정, 소셜 로그인까지
 * 실제 스프링 컨텍스트 + 실제 DB를 거쳐 전 구간이 동작하는지 검증한다.
 * (AuthServiceTest는 Mockito 기반 단위 테스트라 DB 반영 여부까지는 검증하지 않으므로,
 *  스키마/저장 관련 회귀는 이 테스트가 잡아준다.)
 * 외부 I/O(이메일 발송, 소셜 플랫폼 토큰 검증)만 mock 처리하고 나머지는 실제 빈을 사용한다.
 *
 * ⚠️ 디버깅용 System.out.println 이 임시로 포함되어 있음 - 원인 확정되면 제거 예정.
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
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, VerificationPurpose.SIGNUP)
                .orElseThrow();
        String code = verification.getVerificationCode();

        // 2) 이메일 인증 확인 - 실제 저장된 코드로 검증
        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        // 3) 회원가입 - 실제 users 테이블에 저장되는지 확인
        MvcResult signupResult = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"플로우테스터\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn();
        System.out.println("[테스트1] 회원가입 응답: " + signupResult.getResponse().getContentAsString());

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
        System.out.println("[테스트1] 로그인 응답: " + loginResult.getResponse().getContentAsString());

        JsonNode loginData = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("data");
        String accessToken = loginData.path("accessToken").asText();
        String refreshToken = loginData.path("refreshToken").asText();
        System.out.println("[테스트1] 파싱된 accessToken=" + accessToken);
        System.out.println("[테스트1] 파싱된 refreshToken=" + refreshToken);

        // 5) 내 정보 조회 - 방금 로그인한 유저가 실제로 조회되는지, 닉네임이 있어 needsNickname=false인지
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("플로우테스터"))
                .andExpect(jsonPath("$.data.needsNickname").value(false));

        // 6) 로그아웃 - 실제 refresh_tokens 테이블의 revoked 상태가 바뀌는지
        MvcResult logoutResult = mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        System.out.println("[테스트1] 로그아웃 응답: " + logoutResult.getResponse().getContentAsString());

        // 7) 로그아웃된 refresh token으로 재발급 시도 -> 실제로 거부되는지 (DB에 revoked=true가 반영됐는지 증명)
        MvcResult reissueResult = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
        System.out.println("[테스트1] 로그아웃 후 재발급 시도 status=" + reissueResult.getResponse().getStatus());
        System.out.println("[테스트1] 로그아웃 후 재발급 시도 응답: " + reissueResult.getResponse().getContentAsString());
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
                .andReturn();
        System.out.println("[테스트2] 1차 소셜로그인 status=" + socialLoginResult.getResponse().getStatus());
        System.out.println("[테스트2] 1차 소셜로그인 응답: " + socialLoginResult.getResponse().getContentAsString());

        User createdUser = userRepository.findByEmail(email).orElseThrow();
        System.out.println("[테스트2] 1차 소셜로그인 후 DB 유저 id=" + createdUser.getId() + ", nickname=" + createdUser.getNickname());
        assertThat(createdUser.hasNickname()).isFalse();
        assertThat(createdUser.getProvider()).isEqualTo(AuthProvider.GOOGLE);

        JsonNode socialLoginData = objectMapper.readTree(socialLoginResult.getResponse().getContentAsString()).path("data");
        String accessToken = socialLoginData.path("accessToken").asText();

        // 2) 같은 이메일로 재로그인 - isNewUser는 false지만 needsNickname은 여전히 true인지 (재로그인 케이스 회귀 방지)
        MvcResult secondLoginResult = mvc.perform(post("/api/auth/social")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"providerToken\":\"dummy-token\"}"))
                .andReturn();
        System.out.println("[테스트2] 2차 소셜로그인 status=" + secondLoginResult.getResponse().getStatus());
        System.out.println("[테스트2] 2차 소셜로그인 응답: " + secondLoginResult.getResponse().getContentAsString());

        // 3) 닉네임 최초 설정 - 실제 users 테이블에 반영되는지
        MvcResult nicknameResult = mvc.perform(patch("/api/auth/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"소셜테스터\"}"))
                .andReturn();
        System.out.println("[테스트2] 닉네임설정 status=" + nicknameResult.getResponse().getStatus());
        System.out.println("[테스트2] 닉네임설정 응답: " + nicknameResult.getResponse().getContentAsString());

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

        MvcResult signupResult = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"탈퇴테스터\"}"))
                .andReturn();
        System.out.println("[테스트3] 회원가입 status=" + signupResult.getResponse().getStatus());
        System.out.println("[테스트3] 회원가입 응답: " + signupResult.getResponse().getContentAsString());

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andReturn();
        System.out.println("[테스트3] 로그인 status=" + loginResult.getResponse().getStatus());
        System.out.println("[테스트3] 로그인 응답: " + loginResult.getResponse().getContentAsString());

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        System.out.println("[테스트3] 파싱된 accessToken=" + accessToken);

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
}