package Hampouch.server.domain.auth.controller;

import Hampouch.server.domain.auth.dto.response.AuthMeResponse;
import Hampouch.server.domain.auth.dto.response.EmailSendResponse;
import Hampouch.server.domain.auth.dto.response.NicknameCheckResponse;
import Hampouch.server.domain.auth.dto.response.NicknameSetResponse;
import Hampouch.server.domain.auth.service.AuthService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    JwtProvider jwtProvider; // JwtFilter가 Filter 타입이라 슬라이스에 자동 포함되며 요구하는 의존성

    /**
     * @LoginUserId가 붙은 API(닉네임 최초 설정 등)는 LoginUserIdResolver가
     * SecurityContext에서 인증 정보를 꺼내야 하는데, addFilters=false라 JwtFilter가
     * 동작하지 않아 SecurityContext가 비어있다. 실제 필터가 채워주는 것과 동일한 형태로
     * 매 테스트 전에 인증된 사용자(userId=1L)를 직접 세팅해준다.
     */
    @BeforeEach
    void setUpAuthentication() {
        var authentication = new UsernamePasswordAuthenticationToken(1L, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 이메일 발송 ----------

    @Test
    void 이메일발송_이메일형식이_아니면_400() throws Exception {
        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void 이메일발송_purpose가_허용값이_아니면_400() throws Exception {
        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"purpose\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.purpose").exists());
    }

    @Test
    void 이메일발송_정상이면_200과_만료시간을_반환() throws Exception {
        when(authService.sendEmailVerification(any())).thenReturn(EmailSendResponse.of(600L));

        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    void 이메일인증발송_잠금획득에_실패하면_429를_반환한다() throws Exception {
        when(authService.sendEmailVerification(any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@example.com",
                              "purpose": "SIGNUP"
                            }
                            """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_REQUEST_IN_PROGRESS"))
                .andExpect(jsonPath("$.status").value(429));
    }

    // ---------- 이메일 인증 확인 ----------

    @Test
    void 이메일인증확인_코드가_6자리_숫자아니면_400() throws Exception {
        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"code\":\"abc\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").exists());
    }

    @Test
    void 이메일인증_잠금획득에_실패하면_429를_반환한다() throws Exception {
        when(authService.verifyEmail(any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@example.com",
                              "code": "123456",
                              "purpose": "SIGNUP"
                            }
                            """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_REQUEST_IN_PROGRESS"))
                .andExpect(jsonPath("$.status").value(429));
    }

    // ---------- 닉네임 중복확인 ----------

    @Test
    void 닉네임중복확인_2자미만이면_400() throws Exception {
        mvc.perform(get("/api/auth/nickname/check").param("nickname", "아"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 닉네임중복확인_정상이면_200() throws Exception {
        when(authService.checkNickname("햄포치")).thenReturn(NicknameCheckResponse.of("햄포치", true));

        mvc.perform(get("/api/auth/nickname/check").param("nickname", "햄포치"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void 닉네임중복확인_10자초과면_400() throws Exception {
        mvc.perform(get("/api/auth/nickname/check").param("nickname", "가나다라마바사아자차카")) // 11자
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    // ---------- 회원가입 ----------

    @Test
    void 회원가입_비밀번호가_8자미만이면_400() throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"a1\",\"nickname\":\"닉네임\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void 회원가입_닉네임이_10자_초과면_400() throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"password1\",\"nickname\":\"가나다라마바사아자차카\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    void 회원가입_이미_가입된_이메일이면_409() throws Exception {
        when(authService.signup(any())).thenThrow(new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS));

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"password1\",\"nickname\":\"닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_ALREADY_EXISTS"));
    }

    // ---------- 로그인 ----------

    @Test
    void 로그인_비밀번호_불일치시_401() throws Exception {
        when(authService.login(any())).thenThrow(new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_FAILED"));
    }

    // ---------- 소셜 로그인 ----------

    @Test
    void 소셜로그인_provider가_허용값이_아니면_400() throws Exception {
        mvc.perform(post("/api/auth/social")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"NAVER\",\"providerToken\":\"token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.provider").exists());
    }

    // ---------- 토큰 재발급 ----------

    @Test
    void 토큰재발급_refreshToken_없으면_400() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 비밀번호 재설정 ----------

    @Test
    void 비밀번호재설정_소셜계정이면_400() throws Exception {
        doThrow(new CustomException(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED))
                .when(authService).resetPassword(any());

        mvc.perform(patch("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_RESET_NOT_ALLOWED"));
    }

    // ---------- 닉네임 최초 설정 ----------

    @Test
    void 닉네임최초설정_2자미만이면_400() throws Exception {
        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"아\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    void 닉네임최초설정_10자초과면_400() throws Exception {
        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"가나다라마바사아자차카\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    void 닉네임최초설정_공백이면_400() throws Exception {
        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    void 닉네임최초설정_이미_설정된_경우_409() throws Exception {
        when(authService.setInitialNickname(any(), any()))
                .thenThrow(new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_SET));

        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_SET"));
    }

    @Test
    void 닉네임최초설정_중복된_닉네임이면_409() throws Exception {
        when(authService.setInitialNickname(any(), any()))
                .thenThrow(new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS));

        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"중복닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_EXISTS"));
    }

    @Test
    void 닉네임최초설정_정상이면_200() throws Exception {
        when(authService.setInitialNickname(any(), any()))
                .thenReturn(NicknameSetResponse.of(1L, "새닉네임"));

        mvc.perform(patch("/api/auth/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"));
    }

    // ---------- 내 인증/계정 상태 조회 ----------

    @Test
    void 내정보조회_정상이면_200() throws Exception {
        when(authService.getMe(any())).thenReturn(
                AuthMeResponse.of(1L, "테스터", false, "USER", "ACTIVE")
        );

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("테스터"))
                .andExpect(jsonPath("$.data.needsNickname").value(false))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void 내정보조회_닉네임_미설정이면_needsNickname_true_응답() throws Exception {
        when(authService.getMe(any())).thenReturn(
                AuthMeResponse.of(2L, null, true, "USER", "ACTIVE")
        );

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsNickname").value(true));
    }
}