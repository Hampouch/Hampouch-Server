package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.service.ChallengeProgress;
import Hampouch.server.domain.challenge.service.ChallengeProgressQuery;
import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.CategoryAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.EmotionAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.WeekdayAmount;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse.MonthlyAmount;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 지출 분석 API 4종. 기간을 받아 집계만 하는 읽기 전용 책임이라 ExpenseService와 분리해 둔다.
 * ExpenseSpendingQuery를 구현해 Challenge 도메인에 이유별 집계를 좁게 노출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseAnalysisService implements ExpenseSpendingQuery {

    /** 분석 기간 상한 -  챌린지 기간 상한: 100일 */
    private static final int MAX_PERIOD_DAYS = 100;

    /** 추이 그래프의 월 개수 — 요청 파라미터가 아니라 고정값(디자인상 막대가 6개). */
    private static final int TREND_MONTHS = 6;

    private final ExpenseRepository expenseRepository;
    private final Clock clock;

    /** 인사이트 문구 3종. 집계와 분리해 둔 자리 */
    private final ExpenseInsightWriter insightWriter;

    /** 마지막 문장을 고를 때만 쓰는 진행 중 챌린지 조회. ChallengeService가 아니라 좁은 인터페이스를 받아 순환 의존을 피한다. */
    private final ChallengeProgressQuery challengeProgressQuery;

    /**
     * GET /expenses/analysis — 기간 총액 + 카테고리별/이유별/요일별 집계.
     * 달력에서 오면 1일~말일, 챌린지 결과에서 오면 챌린지 기간이 그대로 들어온다.
     * 집계가 전부 userId 스코프라 어느 화면에서 왔는지는 알 필요가 없고 challengeId도 받지 않는다.
     */
    public ExpenseAnalysisResponse analyze(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        List<Expense> expenses = loadPeriod(userId, periodStart, periodEnd);
        long totalAmount = sumPrice(expenses);
        // 집계 3종을 응답과 인사이트가 같이 본다 — 다시 계산하면 화면의 숫자와 문구의 숫자가 어긋날 수 있다
        List<CategoryAmount> categoryBreakdown = categoryBreakdown(expenses, totalAmount);
        List<EmotionAmount> emotionBreakdown = emotionBreakdown(expenses, totalAmount);
        List<WeekdayAmount> weekdayBreakdown = weekdayBreakdown(expenses);

        // 총액이 0원이면 어차피 문장을 만들지 않으므로 챌린지까지 읽지 않는다.
        ChallengeProgress challengeProgress = totalAmount == 0
                ? ChallengeProgress.NONE
                : challengeProgressQuery.overlappingChallengeProgress(userId, periodStart, periodEnd);

        return new ExpenseAnalysisResponse(
                periodStart,
                periodEnd,
                totalAmount,
                categoryBreakdown,
                emotionBreakdown,
                weekdayBreakdown,
                insightWriter.weekdayInsight(weekdayBreakdown, totalAmount),
                insightWriter.pouchInsight(periodFacts(expenses, periodStart, periodEnd, totalAmount,
                        categoryBreakdown, emotionBreakdown, weekdayBreakdown, challengeProgress))
        );
    }

    /**
     * GET /expenses/analysis/category/{category} — 카테고리별 자세히 보기.
     * 기간 전체를 한 번 꺼내 Java에서 거른다. 카테고리 조건을 쿼리에 넣으면 ratio의 분모(기간 총액)를
     * 구하는 쿼리가 따로 필요해지고, 분모가 두 쿼리로 나뉘면 메인 도넛과 어긋날 여지가 생긴다.
     */
    public ExpenseCategoryDetailResponse getCategoryDetail(Long userId, ExpenseCategory category,
                                                           LocalDate periodStart, LocalDate periodEnd) {
        List<Expense> expenses = loadPeriod(userId, periodStart, periodEnd);
        long periodTotal = sumPrice(expenses);

        List<Expense> filtered = expenses.stream()
                .filter(expense -> expense.getCategory() == category)
                .toList();
        long categoryTotal = sumPrice(filtered);

        return new ExpenseCategoryDetailResponse(
                periodStart, periodEnd, category,
                categoryTotal, filtered.size(), percentOf(categoryTotal, periodTotal),
                toItems(filtered)
        );
    }

    /**
     * GET /expenses/analysis/emotion/{emotion} — 지출 이유별 자세히 보기.
     * 커스텀 이유는 별도 값 없이 ETC로 조회된다(내 커스텀 이유 전부가 ETC 한 덩어리로 묶인다).
     */
    public ExpenseEmotionDetailResponse getEmotionDetail(Long userId, ExpenseEmotion emotion,
                                                         LocalDate periodStart, LocalDate periodEnd) {
        List<Expense> expenses = loadPeriod(userId, periodStart, periodEnd);
        long periodTotal = sumPrice(expenses);

        List<Expense> filtered = expenses.stream()
                .filter(expense -> expense.getEmotion() == emotion)
                .toList();
        long emotionTotal = sumPrice(filtered);

        return new ExpenseEmotionDetailResponse(
                periodStart, periodEnd, emotion,
                emotionTotal, filtered.size(), percentOf(emotionTotal, periodTotal),
                toItems(filtered)
        );
    }

    /**
     * GET /expenses/analysis/trend — 최근 6개월 월별 추이. month는 6개월 창의 마지막 달.
     * sumGroupedByDate를 재사용해 날짜별 합계를 Java에서 월로 접는다 — YEAR()/MONTH() JPQL 이식성 문제와
     * 새 프로젝션을 피하는 대신 6행 대신 최대 180행이 오간다.
     */
    public ExpenseTrendResponse getTrend(Long userId, YearMonth month) {
        Objects.requireNonNull(month, "month");
        if (month.isAfter(YearMonth.from(LocalDate.now(clock)))) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_FUTURE_PERIOD);
        }

        YearMonth windowStart = month.minusMonths(TREND_MONTHS - 1L);
        List<ExpenseDailyTotal> dailyTotals = expenseRepository.sumGroupedByDate(
                userId, ExpenseStatus.ACTIVE, windowStart.atDay(1), month.atEndOfMonth());

        Map<YearMonth, Long> amountByMonth = new HashMap<>();
        for (ExpenseDailyTotal daily : dailyTotals) {
            amountByMonth.merge(YearMonth.from(daily.date()), daily.totalAmount(), Long::sum);
        }

        // 지출이 없는 달도 막대가 비어 보이도록 0으로 채워 항상 6개, 오름차순(과거 → 최근)
        List<MonthlyAmount> trend = new ArrayList<>(TREND_MONTHS);
        for (int i = 0; i < TREND_MONTHS; i++) {
            YearMonth target = windowStart.plusMonths(i);
            trend.add(new MonthlyAmount(target, amountByMonth.getOrDefault(target, 0L)));
        }

        long windowSum = trend.stream().mapToLong(MonthlyAmount::amount).sum();
        long currentAmount = trend.get(TREND_MONTHS - 1).amount();
        long previousAmount = trend.get(TREND_MONTHS - 2).amount();
        // 응답 필드와 문구가 같은 값을 봐야 6% 증가라고 적힌 옆에 다른 숫자가 뜨는 일이 없다
        Integer diffRateFromLastMonth = diffRate(currentAmount, previousAmount);

        return new ExpenseTrendResponse(
                month,
                currentAmount,
                // 6개월 합계 ÷ 6 고정 — 지출 0원인 달을 분모에서 빼지 않는다(빼면 기록을 안 한 달일수록 평균이 올라감)
                Math.round(windowSum / (double) TREND_MONTHS),
                diffRateFromLastMonth,
                trend,
                insightWriter.trendInsight(trend, diffRateFromLastMonth)
        );
    }

    /**
     * 기간 검증 후 행 조회 — 분석 3종(메인/카테고리별/이유별)의 공통 진입점.
     * 상한(MAX_PERIOD_DAYS)과 조회를 같은 자리에 둬야 상한을 올릴 때 부하도 같이 보게 된다.
     */
    private List<Expense> loadPeriod(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        validatePeriod(periodStart, periodEnd);
        return expenseRepository.findPeriodExpenses(userId, ExpenseStatus.ACTIVE, periodStart, periodEnd);
    }

    /**
     * 기간 검증 3종. null은 컨트롤러의 필수 @RequestParam이 먼저 막으므로 여기 도달한 null은
     * 사용자 입력이 아니라 내부 호출 버그다 — CustomException이 아니라 NPE로 드러낸다.
     */
    private void validatePeriod(LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");

        if (periodStart.isAfter(periodEnd)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_INVALID_PERIOD);
        }
        // 미래 검증은 startDate에만 건다 — 미래 날짜엔 지출이 없어 endDate를 보정해도 결과가 같다.
        if (periodStart.isAfter(LocalDate.now(clock))) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_FUTURE_PERIOD);
        }
        // 양끝 포함이라 +1. 100일 챌린지는 between == 99라 +1이 없으면 off-by-one이 난다.
        if (ChronoUnit.DAYS.between(periodStart, periodEnd) + 1 > MAX_PERIOD_DAYS) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_PERIOD_TOO_LONG);
        }
    }

    /**
     * ExpenseCategory 8개 전부(0원도 포함), 금액 내림차순. 상위 N + 기타 묶음 규칙은 없다.
     * 도넛에 0원 조각은 안 그려지지만 옆 범례가 ratio 0인 항목까지 적어 준다.
     */
    private List<CategoryAmount> categoryBreakdown(List<Expense> expenses, long totalAmount) {
        Map<ExpenseCategory, Long> amountByCategory = new EnumMap<>(ExpenseCategory.class);
        for (Expense expense : expenses) {
            amountByCategory.merge(expense.getCategory(), (long) expense.getPrice(), Long::sum);
        }

        // 금액이 같을 때 enum 선언 순서로 한 번 더 정렬 — tie-breaker가 없으면 0원 카테고리들의 순서가 매번 달라진다
        Comparator<CategoryAmount> byAmount = Comparator.comparingLong(CategoryAmount::amount);
        Comparator<CategoryAmount> order = byAmount.reversed().thenComparing(CategoryAmount::category);

        return Arrays.stream(ExpenseCategory.values())
                .map(category -> {
                    long amount = amountByCategory.getOrDefault(category, 0L);
                    return new CategoryAmount(category, amount, percentOf(amount, totalAmount));
                })
                .sorted(order)
                .toList();
    }

    /**
     * analyze() 응답 전용 래퍼 — 집계는 emotionSpending()에 위임한다.
     * 챌린지 결과 화면과 공유하는 값만 EmotionSpending으로 따로 두어, 한쪽 응답 모양을 바꿔도 다른 쪽이 흔들리지 않는다.
     */
    private List<EmotionAmount> emotionBreakdown(List<Expense> expenses, long totalAmount) {
        return emotionSpending(expenses, totalAmount).stream()
                .map(es -> new EmotionAmount(es.emotion(), es.amount(), es.ratio()))
                .toList();
    }

    /**
     * ExpenseEmotion 5개 전부(0원도 포함), 금액 내림차순.
     * analyze()와 periodSpending()이 이 메서드 하나를 같이 쓴다 — 집계가 두 벌이면 분석 화면과
     * 챌린지 결과 화면의 숫자가 언젠가 어긋난다.
     */
    private List<EmotionSpending> emotionSpending(List<Expense> expenses, long totalAmount) {
        Map<ExpenseEmotion, Long> amountByEmotion = new EnumMap<>(ExpenseEmotion.class);
        for (Expense expense : expenses) {
            amountByEmotion.merge(expense.getEmotion(), (long) expense.getPrice(), Long::sum);
        }

        Comparator<EmotionSpending> byAmount = Comparator.comparingLong(EmotionSpending::amount);
        Comparator<EmotionSpending> order = byAmount.reversed().thenComparing(EmotionSpending::emotion);

        return Arrays.stream(ExpenseEmotion.values())
                .map(emotion -> {
                    long amount = amountByEmotion.getOrDefault(emotion, 0L);
                    return new EmotionSpending(emotion, amount, percentOf(amount, totalAmount));
                })
                .sorted(order)
                .toList();
    }

    /**
     * 챌린지 결과 화면(소비 감정 분석 그래프·총 지출액) 진입점 — ExpenseSpendingQuery 구현.
     */
    @Override
    public PeriodSpending periodSpending(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        // null / 기간 역전 / 100일 초과시 NPE/IAE로 던진다 — 호출자가 이미 검증된 기간만 넘긴다는 전제
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodStart.isAfter(periodEnd)) {
            throw new IllegalArgumentException(
                    "periodStart(%s)는 periodEnd(%s)보다 늦을 수 없습니다".formatted(periodStart, periodEnd));
        }
        if (ChronoUnit.DAYS.between(periodStart, periodEnd) + 1 > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("조회 기간은 최대 " + MAX_PERIOD_DAYS + "일까지만 허용됩니다");
        }

        // analyze()와 달리 시작일이 미래여도 예외 대신 빈 집계 — 아직 시작 안 한 챌린지 조회를 정상으로 본다.
        List<Expense> expenses = periodStart.isAfter(LocalDate.now(clock))
                ? List.of()
                : expenseRepository.findPeriodExpenses(userId, ExpenseStatus.ACTIVE, periodStart, periodEnd);
        long totalAmount = sumPrice(expenses);
        return new PeriodSpending(totalAmount, emotionSpending(expenses, totalAmount));
    }

    /** 7요일 전부. 이 앱의 주는 일요일 시작이라 DayOfWeek.values() 순서를 쓰면 안 된다. */
    private List<WeekdayAmount> weekdayBreakdown(List<Expense> expenses) {
        Map<DayOfWeek, Long> amountByWeekday = new EnumMap<>(DayOfWeek.class);
        for (Expense expense : expenses) {
            amountByWeekday.merge(expense.getExpenseDate().getDayOfWeek(), (long) expense.getPrice(), Long::sum);
        }

        return WeekdayAmount.DISPLAY_ORDER.stream()
                .map(dayOfWeek -> new WeekdayAmount(dayOfWeek, amountByWeekday.getOrDefault(dayOfWeek, 0L)))
                .toList();
    }

    /**
     * pouchInsight가 문장을 고를 때 보는 사실들. 응답에 실린 집계 3종을 그대로 넘긴다 —
     * 문구가 자기 집계를 다시 돌리면 도넛은 70%인데 문구는 69%라고 말하게 된다.
     * 총액이 0원이면 null을 넘기고, 그때 뭐라고 말할지는 Writer가 정한다.
     */
    private static ExpenseInsightWriter.PeriodFacts periodFacts(
            List<Expense> expenses, LocalDate periodStart, LocalDate periodEnd, long totalAmount,
            List<CategoryAmount> categoryBreakdown, List<EmotionAmount> emotionBreakdown,
            List<WeekdayAmount> weekdayBreakdown, ChallengeProgress challengeProgress) {

        if (totalAmount == 0) {
            return null;
        }

        // 기간을 반으로 가르는 규칙은 Writer에만 둔다 — 여기서 또 계산하면 경계가 하루 어긋나도 아무도 모른다
        LocalDate firstHalfEnd = ExpenseInsightWriter.firstHalfEnd(periodStart, periodEnd);
        long firstHalfAmount = 0;
        long secondHalfAmount = 0;
        Map<ExpenseCategory, Integer> countByCategory = new EnumMap<>(ExpenseCategory.class);
        for (Expense expense : expenses) {
            if (expense.getExpenseDate().isAfter(firstHalfEnd)) {
                secondHalfAmount += expense.getPrice();
            } else {
                firstHalfAmount += expense.getPrice();
            }
            // 금액이 아니라 건수 — 한 번엔 작지만 자주 쓴다는 축은 금액 순위로는 안 보인다
            countByCategory.merge(expense.getCategory(), 1, Integer::sum);
        }

        ExpenseCategory mostFrequentCategory = null;
        int mostFrequentCount = 0;
        for (ExpenseCategory category : ExpenseCategory.values()) { // enum 선언 순서가 곧 동률 tie-breaker
            int count = countByCategory.getOrDefault(category, 0);
            if (count > mostFrequentCount) {
                mostFrequentCategory = category;
                mostFrequentCount = count;
            }
        }

        return new ExpenseInsightWriter.PeriodFacts(
                periodStart, periodEnd, totalAmount,
                categoryBreakdown, emotionBreakdown, weekdayBreakdown,
                topEmotionWithin(expenses, categoryBreakdown.getFirst().category()),
                mostFrequentCategory, mostFrequentCount,
                firstHalfAmount, secondHalfAmount, challengeProgress);
    }

    /**
     * 1위 카테고리 안에서만 집계한 1위 이유. 두 절이 한 문장으로 묶여 나가므로 산정 범위도 같아야 한다 —
     * 기간 전체 1위를 쓰면 카페와 무관한 스트레스 지출로 읽힌다.
     */
    private static ExpenseEmotion topEmotionWithin(List<Expense> expenses, ExpenseCategory category) {
        Map<ExpenseEmotion, Long> amountByEmotion = new EnumMap<>(ExpenseEmotion.class);
        for (Expense expense : expenses) {
            if (expense.getCategory() == category) {
                amountByEmotion.merge(expense.getEmotion(), (long) expense.getPrice(), Long::sum);
            }
        }

        ExpenseEmotion topEmotion = null;
        long topAmount = 0;
        for (ExpenseEmotion emotion : ExpenseEmotion.values()) { // enum 선언 순서가 곧 동률 tie-breaker
            long amount = amountByEmotion.getOrDefault(emotion, 0L);
            if (amount > topAmount) {
                topEmotion = emotion;
                topAmount = amount;
            }
        }
        return topEmotion;
    }

    private static List<ExpenseAnalysisItem> toItems(List<Expense> expenses) {
        return expenses.stream().map(ExpenseAnalysisItem::from).toList();
    }

    /** long으로 합산 — 기간 내 등록 건수에 상한이 없어 int로는 표현할 수 없을 수 있다. */
    private static long sumPrice(List<Expense> expenses) {
        return expenses.stream().mapToLong(Expense::getPrice).sum();
    }

    /**
     * 정수 퍼센트, 분모는 항상 기간 총액. 반올림 탓에 조각 합이 99나 101이 될 수 있어
     * 도넛 각도는 이 값이 아니라 amount로 그려야 한다. 총액 0원이면 0%다.
     */
    private static int percentOf(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }

    /** 지난달 대비 증감률(정수 %, 음수 허용). 지난달이 0원이면 정의되지 않으므로 null이고, 응답에서 키가 생략된다. */
    private static Integer diffRate(long currentAmount, long previousAmount) {
        if (previousAmount == 0) {
            return null;
        }
        return (int) Math.round((currentAmount - previousAmount) * 100.0 / previousAmount);
    }
}
