package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.service.ChallengeProgressQuery;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.CategoryAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.EmotionAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.WeekdayAmount;
import Hampouch.server.domain.expense.dto.ExpenseCategoryDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseEmotionDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse.MonthlyAmount;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 분석 서비스의 기간 검증·집계 규칙. 리포지토리는 Mockito 목 — DB 불필요
 * 쿼리 자체(BETWEEN 양끝 포함, fetch join, 정렬)는 ExpenseRepositoryTest가 담당하고,
 * 여기서는 꺼내온 행을 어떻게 접는가만 본다.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseAnalysisServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER = 1L;

    /** 2026-05-01은 금요일 — 요일 집계 테스트가 이 사실에 의존한다(01=금, 02=토, 03=일). */
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);

    @Mock
    ExpenseRepository expenseRepository;
    @Mock
    ChallengeProgressQuery challengeProgressQuery;

    private ExpenseAnalysisService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        // 인사이트는 목이 아니라 실제 구현을 넣는다 - 의존성이 없는 순수 계산이라 목으로 감싸면
        // 서비스가 문구를 응답에 실어 보내는지가 이 테스트의 관심사인데 그게 사라진다.
        return new ExpenseAnalysisService(
                expenseRepository, clock, new ExpenseInsightWriter(), challengeProgressQuery);
    }

    // ---------- 기간 검증 ----------

    @Test
    @DisplayName("startDate가 endDate보다 늦으면 EXPENSE_ANALYSIS_INVALID_PERIOD")
    void analyze_rejectsReversedPeriod() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .analyze(OWNER, LocalDate.of(2026, 5, 31), LocalDate.of(2026, 5, 1)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_ANALYSIS_INVALID_PERIOD);
    }

    @Test
    @DisplayName("startDate가 미래면 EXPENSE_ANALYSIS_FUTURE_PERIOD")
    void analyze_rejectsFutureStart() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .analyze(OWNER, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_ANALYSIS_FUTURE_PERIOD);
    }

    /**
     * 경계 off-by-one 방지. Challenge.endDate = startDate.plusDays(durationDays - 1)이라
     * 100일 챌린지의 ChronoUnit.DAYS.between은 99다 — 그래서 검사식이 between + 1 > 100이어야 한다.
     */
    @Test
    @DisplayName("정확히 100일(양끝 포함)은 통과한다 — 100일짜리 챌린지 결과 분석이 막히면 안 된다")
    void analyze_allowsExactlyHundredDays() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = start.plusDays(99);
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, start, end))
                .thenReturn(List.of());

        assertThatCode(() -> serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, start, end))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("101일이면 EXPENSE_ANALYSIS_PERIOD_TOO_LONG")
    void analyze_rejectsHundredAndOneDays() {
        LocalDate start = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, start, start.plusDays(100)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_ANALYSIS_PERIOD_TOO_LONG);
    }

    /**
     * 회귀 방지 — 미래 검증을 endDate에도 걸면 가장 빈번한 두 호출이 전부 400이 된다.
     * (1) 이번 달 분석: 5월 10일에 05-01~05-31 조회, (2) 진행 중 챌린지 분석: 종료일이 아직 안 옴.
     */
    @Test
    @DisplayName("endDate가 미래여도 통과한다 — 이번 달 조회와 진행 중 챌린지 조회")
    void analyze_allowsFutureEndDate() {
        LocalDate today = LocalDate.of(2026, 5, 10);
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());
        LocalDate challengeEnd = LocalDate.of(2026, 7, 1);
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, challengeEnd))
                .thenReturn(List.of());

        assertThatCode(() -> {
            serviceAt(today).analyze(OWNER, PERIOD_START, PERIOD_END);
            serviceAt(today).analyze(OWNER, PERIOD_START, challengeEnd);
        }).doesNotThrowAnyException();
    }

    // ---------- analyze 집계 ----------

    /**
     * 8개 전부 내려주는 이유는 도넛 옆 범례가 배달 0%처럼 0인 항목까지 적기 때문이다.
     * 지출 있는 것만 주면 프론트가 enum 목록을 들고 빠진 카테고리를 스스로 채워야 한다.
     */
    @Test
    @DisplayName("카테고리는 8개 전부 금액 내림차순(0원 포함), ratio 분모는 기간 총액이다")
    void analyze_categoryBreakdown() {
        givenPeriodExpenses();

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isEqualTo(10_000);
        assertThat(result.categoryBreakdown()).containsExactly(
                new CategoryAmount(ExpenseCategory.CAFE, 7_000, 70),
                new CategoryAmount(ExpenseCategory.DELIVERY, 3_000, 30),
                // 이하 0원 — 동률이라 enum 선언 순서로 고정된다
                new CategoryAmount(ExpenseCategory.DINING_OUT, 0, 0),
                new CategoryAmount(ExpenseCategory.CONVENIENCE_STORE, 0, 0),
                new CategoryAmount(ExpenseCategory.GROCERY, 0, 0),
                new CategoryAmount(ExpenseCategory.DESSERT, 0, 0),
                new CategoryAmount(ExpenseCategory.DRINKING, 0, 0),
                new CategoryAmount(ExpenseCategory.ETC, 0, 0)
        );
    }

    @Test
    @DisplayName("이유는 5개 전부 나오고(지출 0원 포함) 금액 내림차순, 동률은 enum 선언 순서로 고정된다")
    void analyze_emotionBreakdownIncludesZeroes() {
        givenPeriodExpenses();

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.emotionBreakdown()).containsExactly(
                new EmotionAmount(ExpenseEmotion.STRESS, 8_000, 80),
                new EmotionAmount(ExpenseEmotion.IMPULSE, 2_000, 20),
                new EmotionAmount(ExpenseEmotion.COMPENSATION, 0, 0),
                new EmotionAmount(ExpenseEmotion.CONVENIENCE, 0, 0),
                new EmotionAmount(ExpenseEmotion.ETC, 0, 0)
        );
    }

    /** 이 앱의 주는 일요일 시작이라 DayOfWeek.values()(월요일 시작) 순서를 그대로 쓰면 화면과 어긋난다. */
    @Test
    @DisplayName("요일은 7개 전부, 일요일부터 순서대로 나온다")
    void analyze_weekdayBreakdownStartsOnSunday() {
        givenPeriodExpenses();

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.weekdayBreakdown()).containsExactly(
                new WeekdayAmount(DayOfWeek.SUNDAY, 2_000),
                new WeekdayAmount(DayOfWeek.MONDAY, 0),
                new WeekdayAmount(DayOfWeek.TUESDAY, 0),
                new WeekdayAmount(DayOfWeek.WEDNESDAY, 0),
                new WeekdayAmount(DayOfWeek.THURSDAY, 0),
                new WeekdayAmount(DayOfWeek.FRIDAY, 5_000),
                new WeekdayAmount(DayOfWeek.SATURDAY, 3_000)
        );
    }

    @Test
    @DisplayName("지출이 하나도 없는 기간은 404가 아니라 총액 0 / 전부 0원인 목록으로 응답한다")
    void analyze_emptyPeriod() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isZero();
        // 지출이 하나도 없어도 목록 길이는 그대로다 — 화면이 전부 0%를 그려야 하기 때문
        assertThat(result.categoryBreakdown()).hasSize(8).allMatch(category -> category.amount() == 0 && category.ratio() == 0);
        assertThat(result.emotionBreakdown()).hasSize(5).allMatch(emotion -> emotion.amount() == 0 && emotion.ratio() == 0);
        assertThat(result.weekdayBreakdown()).hasSize(7);
        // 인사이트는 빈 문자열이나 null이 아니라 분석을 제공하지 않는다고 말하는 고정 문구다
        assertThat(result.weekdayInsight()).isEqualTo("지출 기록이 없어 요일 분석을 제공하지 않아요!");
        assertThat(result.pouchInsight()).isEqualTo("지출 기록이 없어 햄포치 분석을 제공하지 않아요!");
    }

    @Test
    @DisplayName("기간 내 지출 합계가 int 범위를 넘어도 예외 없이 그대로 반환한다 — 등록 건수 상한이 없어 int로는 표현 불가능하기 때문(#252 확장)")
    void analyze_handlesTotalBeyondIntRange() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        expense(1, PERIOD_START, 1_000_000_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS),
                        expense(2, PERIOD_START, 1_000_000_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS),
                        expense(3, PERIOD_START, 1_000_000_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS)));

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isEqualTo(3_000_000_000L);
        assertThat(result.categoryBreakdown().getFirst().amount()).isEqualTo(3_000_000_000L);
        assertThat(result.categoryBreakdown().getFirst().ratio()).isEqualTo(100);
    }

    /**
     * 서비스가 인사이트를 실제로 채워 내려주는지 확인 - 문구 분기 자체는 ExpenseInsightWriterTest가 본다.
     * 여기서 확인하는 건 서비스가 Writer에게 넘기는 재료(PeriodFacts)가 맞게 채워졌는가다.
     * 총액 10,000 / 카페 70% / 스트레스 80% / 5월 1~31일이 문장의 숫자 네 개로 그대로 드러난다.
     */
    @Test
    @DisplayName("인사이트 문구는 집계 결과를 그대로 근거로 삼아 채워진다")
    void analyze_insights() {
        givenPeriodExpenses();

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        // 금 5,000 / 토 3,000 - 2위가 1위의 80%에 못 미쳐 배지는 한 요일만 적는다
        assertThat(result.weekdayInsight()).isEqualTo("금요일 지출이 가장 많아요");
        assertThat(result.pouchInsight()).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페가 70%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 80%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 2번째 문장의 이유는 기간 전체 1위가 아니라 1위 카테고리 안에서의 1위여야 한다.
     * 이 데이터는 전체 1위가 스트레스(5,000)인데 카페 안에서는 보상(4,000 > 1,000)이라
     * 서비스가 범위를 잘못 잡으면 2번째 문장이 스트레스로 뒤집힌다 - 3번째 문장은 전체 1위라 스트레스가 맞다.
     */
    @Test
    @DisplayName("1위 카테고리의 이유는 그 카테고리 안에서만 다시 집계한다")
    void analyze_topCategoryReasonIsScopedToThatCategory() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        expense(1L, LocalDate.of(2026, 5, 1), 4_000, ExpenseCategory.CAFE, ExpenseEmotion.COMPENSATION),
                        expense(2L, LocalDate.of(2026, 5, 4), 1_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS),
                        expense(3L, LocalDate.of(2026, 5, 5), 4_000, ExpenseCategory.DELIVERY, ExpenseEmotion.STRESS)
                ));

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.pouchInsight()).isEqualTo(
                "5월 식비는 9,000원이에요."
                        + " 그 중 카페가 56%로 가장 컸고, 대부분 '보상' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 56%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 마지막 문장의 유일한 근거인 전반/후반 분할이 Writer가 정한 경계와 같은지 본다.
     * 5월은 31일이라 전반은 1~15일(15일), 후반은 16~31일(16일)이다.
     * 여기서는 후반 일평균(6,000/16=375)이 전반(2,000/15=133)보다 커서 목표 재설정 쪽 문장이 나온다.
     */
    @Test
    @DisplayName("전반/후반 금액은 Writer가 정한 경계(15일)로 갈라 담는다")
    void analyze_closingUsesFirstHalfBoundary() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        expense(1L, LocalDate.of(2026, 5, 15), 2_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS),
                        expense(2L, LocalDate.of(2026, 5, 16), 6_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS)
                ));

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.pouchInsight()).endsWith("무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?");
    }

    /**
     * 겹침 판정 자체의 경계는 ChallengeProgressReaderTest가 본다.
     * 여기서 확인하는 건 서비스가 그 답을 Writer까지 실어 날라 마지막 문장이 실제로 갈리는가다.
     * 같은 지출·같은 기간인데 챌린지 유무만으로 문장이 바뀌어야 배선이 살아 있는 것이다.
     */
    @Test
    @DisplayName("조회 기간에 겹치는 진행 중 챌린지가 있으면 마지막 문장이 다음 챌린지 제안으로 나간다")
    void analyze_closingFollowsInProgressChallenge() {
        givenPeriodExpenses();
        when(challengeProgressQuery.hasInProgressChallengeOverlapping(OWNER, PERIOD_START, PERIOD_END))
                .thenReturn(true);

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.pouchInsight())
                .endsWith("다음 챌린지에서 식비를 살짝만 줄여보는 것도 추천해요.");
    }

    @Test
    @DisplayName("겹치는 진행 중 챌린지가 없으면 기존 전반/후반 비교 문장으로 돌아간다")
    void analyze_closingFallsBackWithoutChallenge() {
        givenPeriodExpenses();
        when(challengeProgressQuery.hasInProgressChallengeOverlapping(OWNER, PERIOD_START, PERIOD_END))
                .thenReturn(false);

        ExpenseAnalysisResponse result = serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.pouchInsight())
                .endsWith("지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /** 총액이 0원이면 어차피 문장을 만들지 않는다 - 문구 하나 때문에 챌린지를 한 번 더 읽을 이유가 없다. */
    @Test
    @DisplayName("지출이 하나도 없는 기간은 챌린지를 조회하지 않는다")
    void analyze_skipsChallengeLookupWhenNoExpense() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());

        serviceAt(LocalDate.of(2026, 6, 5)).analyze(OWNER, PERIOD_START, PERIOD_END);

        verifyNoInteractions(challengeProgressQuery);
    }

    // ---------- 자세히 보기 ----------

    /**
     * 이 테스트가 지키는 계약: 자세히 보기의 %가 메인 도넛의 %와 같아야 한다.
     * 분모를 필터링된 합계(7,000)로 잘못 잡으면 여기서 100이 나온다.
     */
    @Test
    @DisplayName("카테고리 자세히 보기의 ratio 분모는 카테고리 합계가 아니라 기간 총액이다")
    void getCategoryDetail_ratioUsesPeriodTotal() {
        givenPeriodExpenses();

        ExpenseCategoryDetailResponse result = serviceAt(LocalDate.of(2026, 6, 5))
                .getCategoryDetail(OWNER, ExpenseCategory.CAFE, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isEqualTo(7_000);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.ratio()).isEqualTo(70);
        // 픽스처가 최신순(3L, 2L, 1L)이라 CAFE만 걸러도 그 순서가 그대로 유지돼야 한다(3L 다음 1L)
        assertThat(result.items()).extracting("expenseId").containsExactly(3L, 1L);
    }

    @Test
    @DisplayName("커스텀 카테고리·이유가 붙은 지출은 자세히 보기 항목에 각자의 라벨로 갈라져 나온다")
    void getCategoryDetail_customTagsMapToCorrectLabels() {
        User owner = owner();
        Expense expense = Expense.of("스벅", 5_000, ExpenseCategory.ETC, ExpenseEmotion.ETC,
                LocalDate.of(2026, 5, 10), owner);
        ReflectionTestUtils.setField(expense, "id", 10L);
        expense.assignCustomCategory("N잡");
        expense.assignCustomEmotion("홧김에");
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(expense));

        ExpenseCategoryDetailResponse result = serviceAt(LocalDate.of(2026, 6, 5))
                .getCategoryDetail(OWNER, ExpenseCategory.ETC, PERIOD_START, PERIOD_END);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().categoryLabel()).isEqualTo("N잡");
        assertThat(result.items().getFirst().emotionLabel()).isEqualTo("홧김에");
    }

    /**
     * 카테고리/이유를 건너뛴 지출은 category/emotion=ETC로 흡수되지만 customCategory/customEmotion은
     * null. 분석 집계는 이 둘을 구분하지 않고 같은 ETC 버킷으로 합쳐야 건너뛴 지출은 기타로 분류된다는 요구사항이 성립
     * → 각 항목의 라벨 존재 여부가 갈릴 뿐.
     */
    @Test
    @DisplayName("건너뛰어 customCategory/customEmotion이 없는 ETC 지출도 커스텀 태그가 붙은 ETC 지출과 같은 기타 버킷으로 합산된다")
    void getCategoryDetail_mergesSkippedAndCustomTaggedExpensesIntoSameEtcBucket() {
        User owner = owner();
        Expense skipped = Expense.of(null, 3_000, null, null, LocalDate.of(2026, 5, 10), owner);
        ReflectionTestUtils.setField(skipped, "id", 11L);
        Expense customTagged = Expense.of("스벅", 5_000, ExpenseCategory.ETC, ExpenseEmotion.ETC,
                LocalDate.of(2026, 5, 11), owner);
        ReflectionTestUtils.setField(customTagged, "id", 12L);
        customTagged.assignCustomCategory("N잡");
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(customTagged, skipped)); // 최신순 픽스처와 동일하게 최신이 먼저

        ExpenseCategoryDetailResponse result = serviceAt(LocalDate.of(2026, 6, 5))
                .getCategoryDetail(OWNER, ExpenseCategory.ETC, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isEqualTo(8_000); // 3,000 + 5,000 — 둘 다 같은 버킷
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.items()).extracting("expenseId").containsExactly(12L, 11L);
        assertThat(result.items().get(0).categoryLabel()).isEqualTo("N잡"); // 커스텀 태그 있음
        assertThat(result.items().get(1).categoryLabel()).isNull(); // 건너뛴 쪽은 라벨 없음(응답에선 키 자체 생략)
    }

    @Test
    @DisplayName("해당 기간에 그 카테고리 지출이 없으면 404가 아니라 0 / 0 / 0 / 빈 배열")
    void getCategoryDetail_emptyIsNotNotFound() {
        givenPeriodExpenses();

        ExpenseCategoryDetailResponse result = serviceAt(LocalDate.of(2026, 6, 5))
                .getCategoryDetail(OWNER, ExpenseCategory.GROCERY, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isZero();
        assertThat(result.count()).isZero();
        assertThat(result.ratio()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("이유 자세히 보기도 같은 구조로 동작한다")
    void getEmotionDetail_ratioUsesPeriodTotal() {
        givenPeriodExpenses();

        ExpenseEmotionDetailResponse result = serviceAt(LocalDate.of(2026, 6, 5))
                .getEmotionDetail(OWNER, ExpenseEmotion.STRESS, PERIOD_START, PERIOD_END);

        assertThat(result.emotion()).isEqualTo(ExpenseEmotion.STRESS);
        assertThat(result.totalAmount()).isEqualTo(8_000);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.ratio()).isEqualTo(80);
    }

    // ---------- 추이 ----------

    @Test
    @DisplayName("추이는 항상 6개월이고 지출 없는 달도 0으로 채워 오름차순으로 나온다")
    void getTrend_fillsSixMonths() {
        givenTrendDailyTotals();

        ExpenseTrendResponse result = serviceAt(LocalDate.of(2026, 6, 5)).getTrend(OWNER, YearMonth.of(2026, 5));

        assertThat(result.trend()).containsExactly(
                new MonthlyAmount(YearMonth.of(2025, 12), 0),
                new MonthlyAmount(YearMonth.of(2026, 1), 0),
                new MonthlyAmount(YearMonth.of(2026, 2), 0),
                new MonthlyAmount(YearMonth.of(2026, 3), 0),
                new MonthlyAmount(YearMonth.of(2026, 4), 30_000),
                new MonthlyAmount(YearMonth.of(2026, 5), 20_000)
        );
    }

    /**
     * monthlyAverage의 분모는 6 고정이다. 지출 0원인 달을 분모에서 빼면 기록을 덜 한 달일수록
     * 평균이 올라가는 이상한 지표가 된다(50,000 / 6 = 8,333, 50,000 / 2 = 25,000).
     */
    @Test
    @DisplayName("totalAmount는 선택한 달, monthlyAverage는 6개월 합계 ÷ 6, 증감률은 음수를 허용한다")
    void getTrend_tiles() {
        givenTrendDailyTotals();

        ExpenseTrendResponse result = serviceAt(LocalDate.of(2026, 6, 5)).getTrend(OWNER, YearMonth.of(2026, 5));

        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(result.totalAmount()).isEqualTo(20_000);
        assertThat(result.monthlyAverage()).isEqualTo(8_333);
        assertThat(result.diffRateFromLastMonth()).isEqualTo(-33);
        // 문구의 33%가 위 diffRateFromLastMonth와 같은 값에서 나와야 타일과 문장이 어긋나지 않는다
        assertThat(result.trendInsight()).isEqualTo("가장 식비가 많이 나온 달은 4월, 이번 달은 지난달에 비해 33% 줄었어요.");
    }

    @Test
    @DisplayName("지난달 지출이 0원이면 증감률은 정의되지 않으므로 null이다 (0%로 보이면 오해를 부른다)")
    void getTrend_nullDiffWhenPreviousMonthIsZero() {
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE,
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 5, 31)))
                .thenReturn(List.of(new ExpenseDailyTotal(LocalDate.of(2026, 5, 5), 20_000L)));

        ExpenseTrendResponse result = serviceAt(LocalDate.of(2026, 6, 5)).getTrend(OWNER, YearMonth.of(2026, 5));

        assertThat(result.diffRateFromLastMonth()).isNull();
        // 증감률이 없으면 뒷절을 지어내지 않고 최고 지출 월만 말한다
        assertThat(result.trendInsight()).isEqualTo("가장 식비가 많이 나온 달은 5월이에요.");
    }

    @Test
    @DisplayName("아직 시작하지 않은 달은 EXPENSE_ANALYSIS_FUTURE_PERIOD")
    void getTrend_rejectsFutureMonth() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getTrend(OWNER, YearMonth.of(2026, 7)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_ANALYSIS_FUTURE_PERIOD);
    }

    @Test
    @DisplayName("이번 달은 아직 안 끝났어도 조회된다 (가장 흔한 진입이다)")
    void getTrend_allowsCurrentMonth() {
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of());

        assertThatCode(() -> serviceAt(LocalDate.of(2026, 6, 5)).getTrend(OWNER, YearMonth.of(2026, 6)))
                .doesNotThrowAnyException();
    }

    // ---------- 챌린지 결과 집계(periodSpending, #64) ----------

    /**
     * analyze()의 emotionBreakdown과 완전히 같은 값이 나와야 한다 — 집계 로직을 공유하기 때문
     * (emotionSpending을 emotionBreakdown과 같이 쓴다). 카테고리는 여기 없다 — 실제 결과 화면(Figma)엔
     * 카테고리 그래프가 없어 PeriodSpending에 넣지 않기로 함(담당자 승인, 2026-08-11).
     */
    @Test
    @DisplayName("periodSpending은 analyze()와 같은 이유별 집계 로직을 재사용한다")
    void periodSpending_reusesSameAggregationAsAnalyze() {
        givenPeriodExpenses();

        PeriodSpending result = serviceAt(LocalDate.of(2026, 6, 5)).periodSpending(OWNER, PERIOD_START, PERIOD_END);

        assertThat(result.totalAmount()).isEqualTo(10_000);
        assertThat(result.emotionBreakdown()).containsExactly(
                new EmotionSpending(ExpenseEmotion.STRESS, 8_000, 80),
                new EmotionSpending(ExpenseEmotion.IMPULSE, 2_000, 20),
                new EmotionSpending(ExpenseEmotion.COMPENSATION, 0, 0),
                new EmotionSpending(ExpenseEmotion.CONVENIENCE, 0, 0),
                new EmotionSpending(ExpenseEmotion.ETC, 0, 0)
        );
    }

    /**
     * analyze()는 미래 시작일을 EXPENSE_ANALYSIS_FUTURE_PERIOD(400)로 막지만, periodSpending()은 예외 대신
     * 빈 집계를 돌려준다 — 아직 시작하지 않은 챌린지의 결과 조회를 정상 호출로 취급해야 하기 때문(#64).
     */
    @Test
    @DisplayName("시작일이 미래여도 예외 대신 총액 0 / 전부 0원인 빈 집계를 반환한다")
    void periodSpending_futureStartReturnsEmptyInsteadOfThrowing() {
        LocalDate futureStart = LocalDate.of(2026, 7, 1);
        LocalDate futureEnd = LocalDate.of(2026, 7, 14);

        PeriodSpending result = serviceAt(LocalDate.of(2026, 6, 5)).periodSpending(OWNER, futureStart, futureEnd);

        assertThat(result.totalAmount()).isZero();
        assertThat(result.emotionBreakdown()).hasSize(5).allMatch(e -> e.amount() == 0 && e.ratio() == 0);
    }

    /**
     * null/기간 역전/100일 초과는 analyze()처럼 CustomException(400)이 아니라 NPE/IAE다 — 호출자가
     * 이미 검증된 기간만 넘긴다는 전제라 여기 도달한 위반은 사용자 입력이 아니라 호출자 버그이기 때문(#64).
     */
    @Test
    @DisplayName("userId/periodStart/periodEnd가 null이면 NullPointerException, CustomException이 아니다")
    void periodSpending_rejectsNullWithNpe() {
        var service = serviceAt(LocalDate.of(2026, 6, 5));
        assertThatThrownBy(() -> service.periodSpending(null, PERIOD_START, PERIOD_END))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.periodSpending(OWNER, null, PERIOD_END))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.periodSpending(OWNER, PERIOD_START, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("periodStart가 periodEnd보다 늦으면 IllegalArgumentException, CustomException이 아니다")
    void periodSpending_rejectsReversedPeriodWithIae() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .periodSpending(OWNER, PERIOD_END, PERIOD_START))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("101일이면 IllegalArgumentException, CustomException이 아니다")
    void periodSpending_rejectsHundredAndOneDaysWithIae() {
        LocalDate start = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .periodSpending(OWNER, start, start.plusDays(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(CustomException.class);
    }

    // ---------- fixtures ----------

    /** 총 10,000원 — CAFE 7,000(70%) / DELIVERY 3,000(30%), STRESS 8,000(80%) / IMPULSE 2,000(20%).
     */
    private void givenPeriodExpenses() {
        when(expenseRepository.findPeriodExpenses(OWNER, ExpenseStatus.ACTIVE, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        expense(3L, LocalDate.of(2026, 5, 3), 2_000, ExpenseCategory.CAFE, ExpenseEmotion.IMPULSE),    // 일
                        expense(2L, LocalDate.of(2026, 5, 2), 3_000, ExpenseCategory.DELIVERY, ExpenseEmotion.STRESS), // 토
                        expense(1L, LocalDate.of(2026, 5, 1), 5_000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS)      // 금
                ));
    }

    /** 2026-04 = 30,000 / 2026-05 = 20,000, 나머지 4개월은 행 자체가 없음(0으로 채워져야 함). */
    private void givenTrendDailyTotals() {
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE,
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 5, 31)))
                .thenReturn(List.of(
                        new ExpenseDailyTotal(LocalDate.of(2026, 4, 10), 20_000L),
                        new ExpenseDailyTotal(LocalDate.of(2026, 4, 20), 10_000L),
                        new ExpenseDailyTotal(LocalDate.of(2026, 5, 5), 15_000L),
                        new ExpenseDailyTotal(LocalDate.of(2026, 5, 6), 5_000L)
                ));
    }

    private static Expense expense(long id, LocalDate date, int price, ExpenseCategory category, ExpenseEmotion emotion) {
        Expense expense = Expense.of("지출" + id, price, category, emotion, date, owner());
        ReflectionTestUtils.setField(expense, "id", id);
        return expense;
    }

    private static User owner() {
        User user = User.createLocalUser("user1@hampouch.com", "encoded", "user1");
        ReflectionTestUtils.setField(user, "id", OWNER);
        return user;
    }
}
