package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseImagePresignResponse;
import Hampouch.server.domain.expense.service.ExpenseImageService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
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
import Hampouch.server.global.jwt.JwtProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증 — ExpenseControllerTest와 동일 패턴.
 * 서비스는 목 — S3/DB 불필요.
 */
@WebMvcTest(ExpenseImageController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExpenseImageControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExpenseImageService service;

    @MockitoBean
    JwtProvider jwtProvider;

    private static final Long OWNER = 1L;

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
        when(service.presign(anyLong(), isNull(), any()))
                .thenReturn(ExpenseImagePresignResponse.of("expenses/abc.jpg", "https://bucket.s3.region.amazonaws.com/expenses/abc.jpg?sig=x", 600));

        mvc.perform(post("/api/expenses/photos/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg", "size": 1000 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageKey").value("expenses/abc.jpg"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    @DisplayName("expenseId를 query param으로 보내면 서비스에 그대로 전달된다 — 기존 지출 이미지 교체 시나리오")
    void presign_passesExpenseIdQueryParamToService() throws Exception {
        when(service.presign(anyLong(), eq(5L), any()))
                .thenReturn(ExpenseImagePresignResponse.of("expenses/abc.jpg", "https://bucket.s3.region.amazonaws.com/expenses/abc.jpg?sig=x", 600));

        mvc.perform(post("/api/expenses/photos/presigned")
                        .queryParam("expenseId", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg", "size": 1000 }
                                """))
                .andExpect(status().isOk());

        verify(service).presign(eq(OWNER), eq(5L), any());
    }

    @Test
    @DisplayName("contentType이 지원 형식이 아니면 400으로 거절한다")
    void presign_400_whenContentTypeUnsupported() throws Exception {
        mvc.perform(post("/api/expenses/photos/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "application/pdf", "size": 1000 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size가 없으면 400으로 거절한다")
    void presign_400_whenSizeMissing() throws Exception {
        mvc.perform(post("/api/expenses/photos/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 던진 크기초과 예외를 팀 공통 에러 본문으로 그대로 내려준다")
    void presign_400_whenServiceRejectsOversizedFile() throws Exception {
        when(service.presign(anyLong(), isNull(), any()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_SIZE_EXCEEDED));

        mvc.perform(post("/api/expenses/photos/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentType": "image/jpeg", "size": 999999999 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_IMAGE_SIZE_EXCEEDED"));
    }

    // ---------- attach (PATCH) ----------

    @Test
    @DisplayName("imageKey 반영 요청이 정상이면 200을 반환한다")
    void attach_200() throws Exception {
        mvc.perform(patch("/api/expenses/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "expenses/abc-123.jpg" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("imageKey 형식이 규칙에 맞지 않으면 400으로 거절한다")
    void attach_400_whenImageKeyFormatInvalid() throws Exception {
        mvc.perform(patch("/api/expenses/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "not-a-valid-key" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 업로드 미확인 예외를 던지면 400으로 그대로 내려준다")
    void attach_400_whenImageNotUploaded() throws Exception {
        org.mockito.Mockito.doThrow(new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED))
                .when(service).attach(anyLong(), anyLong(), any());

        mvc.perform(patch("/api/expenses/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "imageKey": "expenses/abc-123.jpg" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_IMAGE_NOT_UPLOADED"));
    }

    // ---------- remove (DELETE) ----------

    @Test
    @DisplayName("이미지 삭제 요청이 정상이면 200을 반환한다")
    void remove_200() throws Exception {
        mvc.perform(delete("/api/expenses/1/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
