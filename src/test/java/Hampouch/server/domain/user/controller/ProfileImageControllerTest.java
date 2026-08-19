package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.response.ProfileImageAttachResponse;
import Hampouch.server.domain.user.dto.response.ProfileImagePresignResponse;
import Hampouch.server.domain.user.service.ProfileImageService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증 — ExpenseImageControllerTest와 동일 패턴.
 * 서비스는 목 — S3/DB 불필요.
 */
@WebMvcTest(ProfileImageController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProfileImageControllerTest {

    private static final Long OWNER = 1L;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProfileImageService service;

    @MockitoBean
    JwtProvider jwtProvider;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- presign ----------

    @Test
    @DisplayName("presign 요청이 정상이면 200과 imageKey/uploadUrl/expiresInSeconds를 반환한다")
    void presign_200() throws Exception {
        when(service.presign(anyLong(), any()))
                .thenReturn(ProfileImagePresignResponse.of("profile/1/abc.jpg", "https://bucket.s3.region.amazonaws.com/profile/1/abc.jpg?sig=x", 600));

        mvc.perform(post("/api/users/me/profile/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg", "size": 1000 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("업로드 URL이 발급되었습니다."))
                .andExpect(jsonPath("$.data.imageKey").value("profile/1/abc.jpg"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    @DisplayName("contentType이 지원 형식이 아니면 400으로 거절한다")
    void presign_400_whenContentTypeUnsupported() throws Exception {
        mvc.perform(post("/api/users/me/profile/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "application/pdf", "size": 1000 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size가 없으면 400으로 거절한다")
    void presign_400_whenSizeMissing() throws Exception {
        mvc.perform(post("/api/users/me/profile/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 던진 크기초과 예외를 팀 공통 에러 본문으로 그대로 내려준다")
    void presign_400_whenServiceRejectsOversizedFile() throws Exception {
        when(service.presign(anyLong(), any()))
                .thenThrow(new CustomException(UserErrorCode.USER_PROFILE_IMAGE_SIZE_EXCEEDED));

        mvc.perform(post("/api/users/me/profile/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg", "size": 999999999 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_SIZE_EXCEEDED"));
    }

    // ---------- attach (PATCH) ----------

    @Test
    @DisplayName("imageKey 반영 요청이 정상이면 200과 imageUrl을 반환한다")
    void attach_200() throws Exception {
        when(service.attach(anyLong(), any()))
                .thenReturn(ProfileImageAttachResponse.of("https://bucket.s3.region.amazonaws.com/profile/1/abc-123.jpg"));

        mvc.perform(patch("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "profile/1/abc-123.jpg" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://bucket.s3.region.amazonaws.com/profile/1/abc-123.jpg"));
    }

    @Test
    @DisplayName("imageKey 형식이 규칙에 맞지 않으면 400으로 거절한다")
    void attach_400_whenImageKeyFormatInvalid() throws Exception {
        mvc.perform(patch("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "not-a-valid-key" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 업로드 미확인 예외를 던지면 400으로 그대로 내려준다")
    void attach_400_whenImageNotUploaded() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED))
                .when(service).attach(anyLong(), any());

        mvc.perform(patch("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "profile/1/abc-123.jpg" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_NOT_UPLOADED"));
    }

    @Test
    @DisplayName("서비스가 소유권 위반 예외를 던지면 403으로 그대로 내려준다")
    void attach_403_whenImageKeyForbidden() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_PROFILE_IMAGE_KEY_FORBIDDEN))
                .when(service).attach(anyLong(), any());

        mvc.perform(patch("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "profile/999/abc-123.jpg" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_KEY_FORBIDDEN"));
    }

    // ---------- remove (DELETE) ----------

    @Test
    @DisplayName("이미지 삭제 요청이 정상이면 200을 반환한다")
    void remove_200() throws Exception {
        mvc.perform(delete("/api/users/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void remove_403_whenUserDeleted() throws Exception {
        doThrow(new CustomException(UserErrorCode.USER_DELETED)).when(service).remove(anyLong());

        mvc.perform(delete("/api/users/me/profile"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }
}
