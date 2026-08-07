package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseSummaryResponse;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import Hampouch.server.global.jwt.JwtProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요
 */
@WebMvcTest(ExpenseController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class ExpenseControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om; // name이 null일 때 JSON 키 자체가 생략되는지 원본 트리로 확인하기 위함

    @MockitoBean
    ExpenseService service;

    @MockitoBean
    JwtProvider jwtProvider; // SecurityConfig가 요구하는 빈 — addFilters=false라 실제 토큰 검증엔 안 쓰임

    private static final Long OWNER = 1L;

    /**
     * @LoginUserId가 읽어갈 인증 정보를 테스트 스레드의 SecurityContextHolder에 직접 심는다.
     * addFilters=false라 SecurityContextPersistenceFilter류가 아예 안 돌기 때문에,
     * .with(authentication(...))처럼 세션에 저장하고 필터가 복원해주길 기대하는 방식은 동작하지 않는다.
     */
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

    @Test
    @DisplayName("생성 요청이 정상이면 201 Created와 Location 헤더, 생성 결과 본문을 돌려준다")
    void create_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new ExpenseCreateResponse(1L));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2026-06-05" }
                                """)
                        )
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/expenses/1"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expenseId").value(1));
    }


    @Test
    @DisplayName("memo/imageKey를 함께 보내도 201로 정상 생성된다")
    void create_201_withMemoAndImageKey() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new ExpenseCreateResponse(1L));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2026-06-05", "memo": "오늘 기분 좋아서", "imageKey": "expenses/abc-123.jpg" }
                                """)
                        )
                .andExpect(status().isCreated())
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
                                """)
                        )
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
                                """)
                        )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("name/category/emotion을 전부 생략해도(건너뛰기) 201로 통과한다 — category=ETC 명시 후 customCategory 누락과는 다름")
    void create_201_whenNameCategoryEmotionSkipped() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new ExpenseCreateResponse(1L));

        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "price": 5000, "date": "2026-06-05" }
                                """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expenseId").value(1));
    }

    @Test
    @DisplayName("미래 날짜로 생성을 요청하면 400으로 거절한다 (@PastOrPresent)")
    void create_400_whenDateInFuture() throws Exception {
        mvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "스타벅스", "price": 5000, "category": "CAFE", "emotion": "STRESS",
                                  "date": "2099-01-01" }
                                """)
                        )
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
                                """)
                        )
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
                                """)
                        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("상세 조회가 정상이면 200과 상세 본문을 돌려준다")
    void getDetail_200() throws Exception {
        when(service.getDetail(anyLong(), anyLong())).thenReturn(new ExpenseDetailResponse(
                1L, "스타벅스", 5000, LocalDate.of(2026, 6, 5), ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, null, null));

        mvc.perform(get("/api/expenses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseId").value(1))
                .andExpect(jsonPath("$.data.name").value("스타벅스"))
                .andExpect(jsonPath("$.data.category").value("CAFE"));
    }


    @Test
    @DisplayName("memo/imageUrl이 있으면 상세 응답에 그대로 포함된다")
    void getDetail_200_includesMemoAndImageUrl() throws Exception {
        when(service.getDetail(anyLong(), anyLong())).thenReturn(new ExpenseDetailResponse(
                1L, "스타벅스", 5000, LocalDate.of(2026, 6, 5), ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, "오늘 기분 좋아서", "https://bucket.s3.region.amazonaws.com/expenses/abc.jpg"));

        mvc.perform(get("/api/expenses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value("오늘 기분 좋아서"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://bucket.s3.region.amazonaws.com/expenses/abc.jpg"));
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
                                """)
                        )
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

    /**
     * ExpenseDayListResponse.ExpenseSummary가 예전엔 레코드 전체에 @JsonInclude(NON_NULL)을
     * 걸고 있어서, name이 nullable해진 뒤 건너뛴 지출은 name 키 자체가 응답에서 사라졌었다.
     * jsonPath(...).doesNotExist()는 값이 null이어도 통과해 이 차이를 못 잡으므로
     * 원본 JSON 트리를 파싱해 키 존재 여부(has())까지 확인한다.
     */
    @Test
    @DisplayName("건너뛴 지출(name=null)도 name 키 자체는 응답에 남고 값만 null이다 — categoryLabel/emotionLabel과 달리 생략되면 안 됨")
    void getDayList_200_keepsNameKeyWhenNull() throws Exception {
        Expense skipped = Expense.of(null, 3000, null, null, LocalDate.of(2026, 6, 5),
                User.createLocalUser("skip@hampouch.com", "encoded", "건너뛴유저"));
        when(service.getDayList(anyLong(), any())).thenReturn(
                ExpenseDayListResponse.from(LocalDate.of(2026, 6, 5), List.of(skipped)));

        String content = mvc.perform(get("/api/expenses/day").param("date", "2026-06-05"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(om.readTree(content).at("/data/expenses/0").has("name")).isTrue();
        assertThat(om.readTree(content).at("/data/expenses/0/name").isNull()).isTrue();
        assertThat(om.readTree(content).at("/data/expenses/0").has("categoryLabel")).isFalse(); // 여긴 계속 생략돼야 함
    }


    @Test
    @DisplayName("주간 요약 조회가 정상이면 200과 기간·합계·일별 내역을 돌려준다")
    void getWeekSummary_200() throws Exception {
        when(service.getWeekSummary(anyLong(), any())).thenReturn(new ExpenseSummaryResponse(
                LocalDate.of(2026, 6, 7), LocalDate.of(2026, 6, 13), 30000, 4285,
                List.of(new ExpenseSummaryResponse.DailyAmount(LocalDate.of(2026, 6, 8), 10000))));

        mvc.perform(get("/api/expenses/summary/week").param("standardDate", "2026-06-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodStart").value("2026-06-07"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-06-13"))
                .andExpect(jsonPath("$.data.totalAmount").value(30000))
                .andExpect(jsonPath("$.data.dailyAverage").value(4285))
                .andExpect(jsonPath("$.data.dailyBreakdown[0].date").value("2026-06-08"))
                .andExpect(jsonPath("$.data.dailyBreakdown[0].totalAmount").value(10000));
    }

    @Test
    @DisplayName("월간 요약 조회가 정상이면 200과 기간·합계·일별 내역을 돌려준다")
    void getMonthSummary_200() throws Exception {
        when(service.getMonthSummary(anyLong(), any())).thenReturn(new ExpenseSummaryResponse(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 60000, 2000,
                List.of(new ExpenseSummaryResponse.DailyAmount(LocalDate.of(2026, 6, 15), 60000))));

        mvc.perform(get("/api/expenses/summary/month").param("standardMonth", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodStart").value("2026-06-01"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-06-30"))
                .andExpect(jsonPath("$.data.totalAmount").value(60000))
                .andExpect(jsonPath("$.data.dailyAverage").value(2000));
    }
}
