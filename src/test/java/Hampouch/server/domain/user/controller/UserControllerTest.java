package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.response.NicknameUpdateResponse;
import Hampouch.server.domain.user.dto.response.UserMeResponse;
import Hampouch.server.domain.user.service.UserService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    JwtProvider jwtProvider; // JwtFilter가 Filter 타입이라 슬라이스에 자동 포함되며 요구하는 의존성

    /**
     * addFilters=false라 JwtFilter가 동작하지 않아 SecurityContext가 비어있다.
     * 실제 필터가 채워주는 것과 동일한 형태로 매 테스트 전에 인증된 사용자(userId=1L)를 직접 세팅해준다.
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

    // ---------- 마이페이지 기본 정보 조회 ----------

    @Test
    @DisplayName("정상 조회면 200과 닉네임·프로필이미지·handle을 반환한다")
    void getMyInfo_returns200WithNicknameProfileImageAndHandle() throws Exception {
        when(userService.getMyInfo(1L))
                .thenReturn(UserMeResponse.of("절약왕 민준", null, "hampochi_minjun@hampouch.com"));

        mvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("절약왕 민준"))
                .andExpect(jsonPath("$.data.handle").value("hampochi_minjun"));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void getMyInfo_returns403WhenUserDeleted() throws Exception {
        when(userService.getMyInfo(1L)).thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        mvc.perform(get("/api/users/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }

    // ---------- 닉네임 변경 ----------

    @Test
    @DisplayName("닉네임이 2자 미만이면 400을 반환한다")
    void updateNickname_returns400WhenShorterThanTwoChars() throws Exception {
        mvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"아\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    @DisplayName("닉네임이 10자를 초과하면 400을 반환한다")
    void updateNickname_returns400WhenLongerThanTenChars() throws Exception {
        mvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"가나다라마바사아자차카\"}")) // 11자
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    @DisplayName("정상 변경이면 200과 바뀐 닉네임을 반환한다")
    void updateNickname_returns200WithChangedNickname() throws Exception {
        when(userService.updateNickname(eq(1L), any())).thenReturn(NicknameUpdateResponse.of("새닉네임"));

        mvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"));
    }

    @Test
    @DisplayName("이미 존재하는 닉네임이면 409를 반환한다")
    void updateNickname_returns409WhenNicknameAlreadyExists() throws Exception {
        when(userService.updateNickname(eq(1L), any()))
                .thenThrow(new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS));

        mvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"중복닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void updateNickname_returns403WhenUserDeleted() throws Exception {
        when(userService.updateNickname(eq(1L), any())).thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        mvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }

    // ---------- 비밀번호 변경 ----------

    @Test
    @DisplayName("정상 변경이면 200을 반환한다")
    void changePassword_returns200() throws Exception {
        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current1\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("새 비밀번호가 형식(8자 이상 + 영문/숫자)을 어기면 400을 반환한다")
    void changePassword_returns400WhenNewPasswordViolatesPolicy() throws Exception {
        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current1\",\"newPassword\":\"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
    }

    @Test
    @DisplayName("현재 비밀번호가 일치하지 않으면 400을 반환한다")
    void changePassword_returns400WhenCurrentPasswordMismatch() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_CURRENT_PASSWORD_MISMATCH))
                .when(userService).changePassword(eq(1L), any());

        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrongPassword1\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_CURRENT_PASSWORD_MISMATCH"));
    }

    @Test
    @DisplayName("소셜 로그인 계정이면 403을 반환한다")
    void changePassword_returns403WhenSocialUser() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_SOCIAL_PASSWORD_CHANGE_NOT_ALLOWED))
                .when(userService).changePassword(eq(1L), any());

        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current1\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SOCIAL_PASSWORD_CHANGE_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void changePassword_returns403WhenUserDeleted() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_DELETED))
                .when(userService).changePassword(eq(1L), any());

        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current1\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }
}
