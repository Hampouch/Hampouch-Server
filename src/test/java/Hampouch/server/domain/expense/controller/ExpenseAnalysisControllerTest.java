package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseAnalysisItem;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.CategoryAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.EmotionAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.WeekdayAmount;
import Hampouch.server.domain.expense.dto.ExpenseCategoryDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseEmotionDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse.MonthlyAmount;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseAnalysisService;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 4종의 웹 계층 검증 — 라우팅, 파라미터 바인딩, 직렬화 계약, 에러 코드 매핑.
 * - 기간 규칙(시작일 역전 / 미래 시작일 / 100일 상한 / 종료일이 미래여도 통과)은 여기서 다시 확인하지 않는다.
 *   서비스가 목이라 그 규칙들은 이 파일에선 아무 일도 하지 않고, 진짜 판정은 ExpenseAnalysisServiceTest가
 *   실제 로직으로 이미 잠가 두었다. 여기서 흉내만 낸 테스트를 하나 더 두면 규칙이 바뀌었을 때
 *   초록불인 채로 남아 오히려 안전하다고 착각하게 만든다.
 * - 필수 쿼리 파라미터가 아예 안 온 경우(현재 500)도 여기서 다루지 않는다 — GlobalExceptionHandler에
 *   핸들러를 더하는 별도 이슈의 몫이고, 그 검증은 핸들러와 함께 들어와야 의미가 있다.
 * ExpenseController를 같이 올리는 건 라우팅 때문이다 — 아래 경로 충돌 테스트 참고.
 */
@WebMvcTest({ExpenseAnalysisController.class, ExpenseController.class})
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class ExpenseAnalysisControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @MockitoBean
    ExpenseAnalysisService analysisService;

    @MockitoBean
    ExpenseService expenseService; // 같이 올린 ExpenseController가 요구하는 빈 — 이 테스트에선 쓰지 않는다

    @MockitoBean
    JwtProvider jwtProvider; // SecurityConfig가 요구하는 빈 — addFilters=false라 실제 토큰 검증엔 안 쓰임

    private static final Long OWNER = 1L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);

    /** ExpenseControllerTest와 동일 — @LoginUserId가 읽어갈 인증 정보를 테스트 스레드에 직접 심는다. */
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
    @DisplayName("분석 메인 조회가 정상이면 200과 기간·총액·집계 3종·문구를 돌려준다")
    void analyze_200() throws Exception {
        when(analysisService.analyze(anyLong(), any(), any())).thenReturn(analysisResponse());

        mvc.perform(get("/api/expenses/analysis")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                // 서버가 요청을 어떻게 해석했는지 응답만 보고 확인할 수 있어야 한다는 DTO의 계약
                .andExpect(jsonPath("$.data.periodStart").value("2026-05-01"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-05-31"))
                .andExpect(jsonPath("$.data.totalAmount").value(10000))
                .andExpect(jsonPath("$.data.categoryBreakdown[0].category").value("CAFE"))
                .andExpect(jsonPath("$.data.categoryBreakdown[0].ratio").value(70))
                .andExpect(jsonPath("$.data.emotionBreakdown[0].emotion").value("STRESS"))
                // 요일은 일요일부터 — DayOfWeek의 자연 순서(월요일 시작)를 그대로 쓰면 화면과 어긋난다
                .andExpect(jsonPath("$.data.weekdayBreakdown[0].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.data.weekdayInsight").value("금요일 지출이 가장 많아요"))
                .andExpect(jsonPath("$.data.pouchInsight").value("5월 식비는 10,000원이에요."));
    }

    @Test
    @DisplayName("/analysis 경로가 지출 단건 조회(/{expenseId})로 새지 않는다")
    void analyze_pathDoesNotFallIntoExpenseIdPattern() throws Exception {
        // /api/expenses/analysis는 /api/expenses/{expenseId}와 같은 자리를 두고 겹친다.
        // 스프링이 글자 그대로인 조각을 변수 조각보다 먼저 고르기 때문에 분석 쪽이 이기는데,
        // 이건 우리 코드가 아니라 프레임워크 규칙이라 실제로 어느 핸들러가 불렸는지로 못 박아 둔다.
        // 만약 단건 조회로 샜다면 analysis를 Long으로 바꾸지 못해 400이 났을 것이다.
        when(analysisService.analyze(anyLong(), any(), any())).thenReturn(analysisResponse());

        mvc.perform(get("/api/expenses/analysis")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isOk());

        verify(analysisService).analyze(OWNER, PERIOD_START, PERIOD_END);
    }

    @Test
    @DisplayName("쿼리로 온 기간은 해석만 하고 그대로 서비스에 넘긴다")
    void analyze_passesParsedPeriodToService() throws Exception {
        // 컨트롤러가 기간을 보정하거나 잘라내지 않는다는 계약. 예를 들어 종료일이 미래라고 오늘로 당겨 버리면
        // 응답의 periodEnd가 요청과 달라져, 화면이 보낸 기간과 서버가 계산한 기간이 조용히 어긋난다.
        when(analysisService.analyze(anyLong(), any(), any())).thenReturn(analysisResponse());

        mvc.perform(get("/api/expenses/analysis")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isOk());

        verify(analysisService).analyze(OWNER, PERIOD_START, PERIOD_END);
    }

    @Test
    @DisplayName("날짜 형식이 틀리면 400과 어느 파라미터가 문제인지 돌려준다")
    void analyze_invalidDateFormat_400() throws Exception {
        mvc.perform(get("/api/expenses/analysis")
                        .param("periodStart", "2026-5-1")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.periodStart").exists());
    }

    @Test
    @DisplayName("서비스가 기간 규칙으로 거절하면 그 에러 코드가 그대로 응답에 실린다")
    void analyze_serviceRejection_maps400() throws Exception {
        // 몇 일부터 너무 긴 기간인지를 여기서 정하지 않는다 — 그 판정은 ExpenseAnalysisServiceTest 몫이고,
        // 이 테스트가 확인하는 건 거절이 400 + 약속된 코드로 번역돼 나간다는 것뿐이다.
        when(analysisService.analyze(anyLong(), any(), any()))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_PERIOD_TOO_LONG));

        mvc.perform(get("/api/expenses/analysis")
                        .param("periodStart", "2026-01-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_ANALYSIS_PERIOD_TOO_LONG"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("카테고리별 자세히 보기는 경로의 카테고리를 그대로 넘기고 200과 목록을 돌려준다")
    void getCategoryDetail_200() throws Exception {
        when(analysisService.getCategoryDetail(anyLong(), any(), any(), any())).thenReturn(
                new ExpenseCategoryDetailResponse(
                        PERIOD_START, PERIOD_END, ExpenseCategory.DELIVERY, 4000, 1, 40,
                        List.of(new ExpenseAnalysisItem(
                                1L, LocalDate.of(2026, 5, 4), "치킨",
                                ExpenseCategory.DELIVERY, null, ExpenseEmotion.CONVENIENCE, null, 4000))));

        String content = mvc.perform(get("/api/expenses/analysis/category/DELIVERY")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("DELIVERY"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.ratio").value(40))
                .andExpect(jsonPath("$.data.items[0].name").value("치킨"))
                .andReturn().getResponse().getContentAsString();

        assertThat(om.readTree(content).at("/data/items/0").has("categoryLabel")).isFalse();
        verify(analysisService).getCategoryDetail(OWNER, ExpenseCategory.DELIVERY, PERIOD_START, PERIOD_END);
    }

    @Test
    @DisplayName("없는 카테고리 이름으로 부르면 400을 돌려준다")
    void getCategoryDetail_unknownCategory_400() throws Exception {
        // 경로 변수를 enum으로 못 바꾸면 스프링이 던지고, GlobalExceptionHandler가 400으로 옮긴다.
        mvc.perform(get("/api/expenses/analysis/category/COFFEE")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("이유별 자세히 보기도 같은 구조로 응답한다")
    void getEmotionDetail_200() throws Exception {
        when(analysisService.getEmotionDetail(anyLong(), any(), any(), any())).thenReturn(
                new ExpenseEmotionDetailResponse(
                        PERIOD_START, PERIOD_END, ExpenseEmotion.STRESS, 8000, 2, 80, List.of()));

        mvc.perform(get("/api/expenses/analysis/emotion/STRESS")
                        .param("periodStart", "2026-05-01")
                        .param("periodEnd", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emotion").value("STRESS"))
                .andExpect(jsonPath("$.data.totalAmount").value(8000))
                .andExpect(jsonPath("$.data.items").isArray());

        verify(analysisService).getEmotionDetail(OWNER, ExpenseEmotion.STRESS, PERIOD_START, PERIOD_END);
    }

    @Test
    @DisplayName("추이 조회는 달을 2026-05 꼴로 직렬화하고 6개월치를 돌려준다")
    void getTrend_200() throws Exception {
        when(analysisService.getTrend(anyLong(), any())).thenReturn(trendResponse(-6));

        mvc.perform(get("/api/expenses/analysis/trend").param("month", "2026-05"))
                .andExpect(status().isOk())
                // 배열이 아니라 문자열이어야 한다 — 직렬화 설정이 바뀌면 조용히 [2026,5]로 변하는 자리다
                .andExpect(jsonPath("$.data.month").value("2026-05"))
                .andExpect(jsonPath("$.data.trend.length()").value(6))
                .andExpect(jsonPath("$.data.trend[0].month").value("2025-12"))
                .andExpect(jsonPath("$.data.diffRateFromLastMonth").value(-6));

        verify(analysisService).getTrend(OWNER, YearMonth.of(2026, 5));
    }

    @Test
    @DisplayName("증감률이 정의되지 않으면 키 자체를 내리지 않는다")
    void getTrend_nullDiffRateIsOmitted() throws Exception {
        // 0으로 내리면 화면이 변화 없음으로 그린다 — 지난달 지출이 0원이라 계산 자체가 안 되는 것과는 다른 뜻이다.
        when(analysisService.getTrend(anyLong(), any())).thenReturn(trendResponse(null));

        mvc.perform(get("/api/expenses/analysis/trend").param("month", "2026-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diffRateFromLastMonth").doesNotExist());
    }

    private static ExpenseAnalysisResponse analysisResponse() {
        return new ExpenseAnalysisResponse(
                PERIOD_START, PERIOD_END, 10_000,
                List.of(new CategoryAmount(ExpenseCategory.CAFE, 7_000, 70),
                        new CategoryAmount(ExpenseCategory.DELIVERY, 3_000, 30)),
                List.of(new EmotionAmount(ExpenseEmotion.STRESS, 8_000, 80),
                        new EmotionAmount(ExpenseEmotion.IMPULSE, 2_000, 20)),
                WeekdayAmount.DISPLAY_ORDER.stream()
                        .map(day -> new WeekdayAmount(day, day == DayOfWeek.FRIDAY ? 10_000 : 0))
                        .toList(),
                "금요일 지출이 가장 많아요",
                "5월 식비는 10,000원이에요.");
    }

    private static ExpenseTrendResponse trendResponse(Integer diffRateFromLastMonth) {
        YearMonth month = YearMonth.of(2026, 5);
        List<MonthlyAmount> trend = List.of(
                new MonthlyAmount(month.minusMonths(5), 9_000),
                new MonthlyAmount(month.minusMonths(4), 0),
                new MonthlyAmount(month.minusMonths(3), 12_000),
                new MonthlyAmount(month.minusMonths(2), 11_000),
                new MonthlyAmount(month.minusMonths(1), 10_640),
                new MonthlyAmount(month, 10_000));
        return new ExpenseTrendResponse(month, 10_000, 8_773, diffRateFromLastMonth, trend, "지난달보다 조금 줄었어요");
    }
}
