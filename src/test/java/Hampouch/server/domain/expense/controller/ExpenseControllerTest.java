package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import Hampouch.server.global.jwt.JwtProvider;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요(ChallengeControllerTest와 동일 스타일).
 */
@WebMvcTest(ExpenseController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class ExpenseControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExpenseService service;

    @MockitoBean
    JwtProvider jwtProvider; //임시 추가

    @Test
    @DisplayName("생성 요청이 정상이면 201 Created와 Location 헤더, 생성 결과 본문을 돌려준다")
    void create_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new ExpenseCreateResponse(1L));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2026-06-05" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/expenses/1"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expenseId").value(1));
    }

    @Test
    @DisplayName("price가 1보다 작으면 400으로 거절한다")
    void create_400_whenPriceBelowMin() throws Exception {
        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 0, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2026-06-05" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("category가 ETC인데 customCategory를 안 보내면 400으로 거절한다 (isCategoryConsistent)")
    void create_400_whenEtcCategoryMissingCustomCategory() throws Exception {
        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스터디카페 이용권", "price": 8000, "category": "ETC", "emotion": "STRESS",
                                  "date": "2026-06-05" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("미래 날짜로 생성을 요청하면 400으로 거절한다 (@PastOrPresent)")
    void create_400_whenDateInFuture() throws Exception {
        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2099-01-01" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("커스텀 카테고리 이름이 내장 라벨과 겹치면 서비스가 던진 409를 팀 공통 에러 본문으로 그대로 내려준다")
    void create_409_whenCustomCategoryDuplicatesBuiltinLabel() throws Exception {
        when(service.create(anyLong(), any()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "아이스아메리카노", "price": 4500, "category": "ETC", "customCategory": "카페",
                                  "emotion": "STRESS", "date": "2026-06-05" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("커스텀 감정 이름이 내장 라벨과 겹치면 서비스가 던진 409를 팀 공통 에러 본문으로 그대로 내려준다 — customCategory와 대칭 케이스")
    void create_409_whenCustomEmotionDuplicatesBuiltinLabel() throws Exception {
        when(service.create(anyLong(), any()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE",
                                  "emotion": "ETC", "customEmotion": "스트레스", "date": "2026-06-05" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("상세 조회가 정상이면 200과 상세 본문을 돌려준다")
    void getDetail_200() throws Exception {
        when(service.getDetail(anyLong(), anyLong())).thenReturn(new ExpenseDetailResponse(
                1L, "스타벅스", 5000, LocalDate.of(2026, 6, 5), ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null));

        mvc.perform(get("/api/expenses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseId").value(1))
                .andExpect(jsonPath("$.data.name").value("스타벅스"))
                .andExpect(jsonPath("$.data.category").value("CAFE"));
    }

    @Test
    @DisplayName("존재하지 않는 지출을 조회하면 404를 돌려준다")
    void getDetail_404() throws Exception {
        when(service.getDetail(anyLong(), anyLong()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_NOT_FOUND));

        mvc.perform(get("/api/expenses/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("남의 지출을 조회하면 403을 돌려준다")
    void getDetail_403() throws Exception {
        when(service.getDetail(anyLong(), anyLong()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_FORBIDDEN));

        mvc.perform(get("/api/expenses/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("수정 요청이 정상이면 200과 (POST와 동일한) 생성 응답 형태를 돌려준다")
    void update_200() throws Exception {
        when(service.update(anyLong(), anyLong(), any())).thenReturn(new ExpenseCreateResponse(1L));

        mvc.perform(put("/api/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 6000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2026-06-05" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseId").value(1));
    }

    @Test
    @DisplayName("삭제 요청이 정상이면 200과 완료 메시지를 돌려준다")
    void delete_200() throws Exception {
        mvc.perform(delete("/api/expenses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("지출 내역이 삭제되었습니다."));
    }

    @Test
    @DisplayName("하루 목록 조회가 정상이면 200과 합계·목록을 돌려준다")
    void getDayList_200() throws Exception {
        when(service.getDayList(anyLong(), any())).thenReturn(new ExpenseDayListResponse(
                LocalDate.of(2026, 6, 5), 5000, List.of()));

        mvc.perform(get("/api/expenses/day").param("date", "2026-06-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-06-05"))
                .andExpect(jsonPath("$.data.totalAmount").value(5000));
    }
}
