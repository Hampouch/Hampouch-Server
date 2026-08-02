package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.ExpenseAnalysisItem;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 지출 분석 API 4종의 서비스 계층 — ExpenseService(5개 우선순위 API)와 분리
 * 분석은 기간을 받아 집계만 하는 읽기 전용 책임, ExpenseService와 공유할 상태가 하나도 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseAnalysisService {

    /** 분석 기간 상한 -  챌린지 기간 상한: 100일 */
    private static final int MAX_PERIOD_DAYS = 100;

    /** 추이 그래프의 월 개수 — 요청 파라미터가 아니라 고정값(디자인상 막대가 6개). */
    private static final int TREND_MONTHS = 6;

    private final ExpenseRepository expenseRepository;
    private final Clock clock;

    /** 인사이트 문구 3종. 집계와 분리해 둔 자리 */
    private final ExpenseInsightWriter insightWriter;

    /**
     * GET /expenses/analysis — 기간 총액 + 카테고리별/이유별/요일별 집계.
     * 달력에서 오면 그 달의 1일~말일, 챌린지 결과에서 오면 챌린지 시작일~종료일이 그대로 들어온다.
     * 어느 화면에서 왔는지는 알 필요가 없고 challengeId도 받지 않는다 — 모든 집계가 userId 스코프 안에서만 돌기 때문.
     */
    public ExpenseAnalysisResponse analyze(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        List<Expense> expenses = loadPeriod(userId, periodStart, periodEnd);
        int totalAmount = sumPrice(expenses);
        // 집계 3종을 응답과 인사이트가 같이 본다 — 다시 계산하면 화면의 숫자와 문구의 숫자가 어긋날 수 있다
        List<CategoryAmount> categoryBreakdown = categoryBreakdown(expenses, totalAmount);
        List<EmotionAmount> emotionBreakdown = emotionBreakdown(expenses, totalAmount);
        List<WeekdayAmount> weekdayBreakdown = weekdayBreakdown(expenses);

        return new ExpenseAnalysisResponse(
                periodStart,
                periodEnd,
                totalAmount,
                categoryBreakdown,
                emotionBreakdown,
                weekdayBreakdown,
                insightWriter.weekdayInsight(weekdayBreakdown, totalAmount),
                insightWriter.pouchInsight(periodFacts(expenses, periodStart, periodEnd, totalAmount,
                        categoryBreakdown, emotionBreakdown, weekdayBreakdown))
        );
    }

    /**
     * GET /expenses/analysis/category/{category} — 카테고리별 자세히 보기.
     * 기간 전체를 한 번 꺼내 Java에서 거른다. 카테고리 조건을 쿼리에 넣으면 ratio의 분모(기간 총액)를 못 구해
     * 어차피 두 번째 쿼리가 필요해지기 때문 — 그러면 분모가 두 쿼리에 나뉘어 메인 도넛과 어긋날 여지가 생긴다.
     */
    public ExpenseCategoryDetailResponse getCategoryDetail(Long userId, ExpenseCategory category,
                                                           LocalDate periodStart, LocalDate periodEnd) {
        List<Expense> expenses = loadPeriod(userId, periodStart, periodEnd);
        int periodTotal = sumPrice(expenses);

        List<Expense> filtered = expenses.stream()
                .filter(expense -> expense.getCategory() == category)
                .toList();
        int categoryTotal = sumPrice(filtered);

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
        int periodTotal = sumPrice(expenses);

        List<Expense> filtered = expenses.stream()
                .filter(expense -> expense.getEmotion() == emotion)
                .toList();
        int emotionTotal = sumPrice(filtered);

        return new ExpenseEmotionDetailResponse(
                periodStart, periodEnd, emotion,
                emotionTotal, filtered.size(), percentOf(emotionTotal, periodTotal),
                toItems(filtered)
        );
    }

    /**
     * GET /expenses/analysis/trend — 최근 6개월 월별 추이. month는 6개월 창의 마지막 달.
     * sumGroupedByDate를 재사용, 날짜별 합계를 받아 Java에서 월로 접는다
     * 합의 합이라 값이 정확하고, 새 JPQL(YEAR()/MONTH() 이식성 문제)과
     * 새 프로젝션 record를 늘리지 않아도 된다. 대가는 6행 대신 최대 180행이 오가는 것뿐.
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
            // toIntExact: 한 달 합계가 int를 넘으면(현실적으로 불가) 조용히 음수로 뒤집히는 대신 예외로 드러나게 한다
            trend.add(new MonthlyAmount(target, Math.toIntExact(amountByMonth.getOrDefault(target, 0L))));
        }

        int windowSum = trend.stream().mapToInt(MonthlyAmount::amount).sum();
        int currentAmount = trend.get(TREND_MONTHS - 1).amount();
        int previousAmount = trend.get(TREND_MONTHS - 2).amount();
        // 응답 필드와 문구가 같은 값을 봐야 6% 증가라고 적힌 옆에 다른 숫자가 뜨는 일이 없다
        Integer diffRateFromLastMonth = diffRate(currentAmount, previousAmount);

        return new ExpenseTrendResponse(
                month,
                currentAmount,
                // 6개월 합계 ÷ 6 고정 — 지출 0원인 달을 분모에서 빼지 않는다(빼면 기록을 안 한 달일수록 평균이 올라감)
                (int) Math.round(windowSum / (double) TREND_MONTHS),
                diffRateFromLastMonth,
                trend,
                insightWriter.trendInsight(trend, diffRateFromLastMonth)
        );
    }

    /**
     * 기간 검증 후 행 조회 — 분석 3종(메인/카테고리별/이유별)의 공통 진입점.
     * 검증을 여기 두는 이유는 조회 크기 상한(MAX_PERIOD_DAYS)과 조회가 같은 자리에 있어야
     * 나중에 상한을 올릴 때 부하를 같이 보게 되기 때문이다.
     */
    private List<Expense> loadPeriod(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        validatePeriod(periodStart, periodEnd);
        return expenseRepository.findPeriodExpenses(userId, ExpenseStatus.ACTIVE, periodStart, periodEnd);
    }

    /**
     * 기간 검증 3종. 파라미터 누락(null)은 여기서 잡지 않는다 — 컨트롤러의 필수 @RequestParam이 먼저 막고,
     * 그 응답을 500이 아니라 400으로 만드는 건 GlobalExceptionHandler 몫이다(별도 이슈).
     * 그래서 여기 도달한 null은 사용자 입력이 아니라 내부 호출 버그이므로 CustomException이 아니라 NPE로 드러낸다.
     */
    private void validatePeriod(LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");

        if (periodStart.isAfter(periodEnd)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_INVALID_PERIOD);
        }
        // 미래 검증은 startDate에만 건다. endDate에 걸면 이번 달 분석(5월 10일에 05-01~05-31 조회)과
        // 진행 중 챌린지 분석이 전부 400 Error 발생
        // 미래 날짜엔 지출이 없어 잘라도 집계 결과가 같으므로 서버가 endDate를 보정할 필요도 없다.
        if (periodStart.isAfter(LocalDate.now(clock))) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_FUTURE_PERIOD);
        }
        // 양끝 포함이라 +1. Challenge.endDate = startDate.plusDays(durationDays - 1)이므로
        // 100일 챌린지는 between == 99이고, +1 없이 비교하면 100일 챌린지가 101일로 잡히거나(> 99)
        // 101일이 통과하는(> 100) off-by-one이 난다.
        if (ChronoUnit.DAYS.between(periodStart, periodEnd) + 1 > MAX_PERIOD_DAYS) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_ANALYSIS_PERIOD_TOO_LONG);
        }
    }

    /**
     * ExpenseCategory 8개 전부(지출 0원인 카테고리도 amount 0으로 포함), 금액 내림차순.
     * 상위 N + 기타 묶음 규칙은 없다.
     * 도넛에는 0원 조각이 그려지지 않지만 옆 범례가 ratio가 0인 항목까지 적어주기 때문에 8개를 다 내려준다.
     */
    private List<CategoryAmount> categoryBreakdown(List<Expense> expenses, int totalAmount) {
        Map<ExpenseCategory, Integer> amountByCategory = new EnumMap<>(ExpenseCategory.class);
        for (Expense expense : expenses) {
            amountByCategory.merge(expense.getCategory(), expense.getPrice(), Integer::sum);
        }

        // 금액이 같을 때 enum 선언 순서로 한 번 더 정렬 — tie-breaker가 없으면 0원 카테고리들의 순서가 매번 달라진다
        Comparator<CategoryAmount> byAmount = Comparator.comparingInt(CategoryAmount::amount);
        Comparator<CategoryAmount> order = byAmount.reversed().thenComparing(CategoryAmount::category);

        return Arrays.stream(ExpenseCategory.values())
                .map(category -> {
                    int amount = amountByCategory.getOrDefault(category, 0);
                    return new CategoryAmount(category, amount, percentOf(amount, totalAmount));
                })
                .sorted(order)
                .toList();
    }

    /** ExpenseEmotion 5개 전부(지출 0원인 이유도 amount 0으로 포함), 금액 내림차순. */
    private List<EmotionAmount> emotionBreakdown(List<Expense> expenses, int totalAmount) {
        Map<ExpenseEmotion, Integer> amountByEmotion = new EnumMap<>(ExpenseEmotion.class);
        for (Expense expense : expenses) {
            amountByEmotion.merge(expense.getEmotion(), expense.getPrice(), Integer::sum);
        }

        Comparator<EmotionAmount> byAmount = Comparator.comparingInt(EmotionAmount::amount);
        Comparator<EmotionAmount> order = byAmount.reversed().thenComparing(EmotionAmount::emotion);

        return Arrays.stream(ExpenseEmotion.values())
                .map(emotion -> {
                    int amount = amountByEmotion.getOrDefault(emotion, 0);
                    return new EmotionAmount(emotion, amount, percentOf(amount, totalAmount));
                })
                .sorted(order)
                .toList();
    }

    /** 7요일 전부. 이 앱의 주는 일요일 시작이라 DayOfWeek.values() 순서를 쓰면 안 된다. */
    private List<WeekdayAmount> weekdayBreakdown(List<Expense> expenses) {
        Map<DayOfWeek, Integer> amountByWeekday = new EnumMap<>(DayOfWeek.class);
        for (Expense expense : expenses) {
            amountByWeekday.merge(expense.getExpenseDate().getDayOfWeek(), expense.getPrice(), Integer::sum);
        }

        return WeekdayAmount.DISPLAY_ORDER.stream()
                .map(dayOfWeek -> new WeekdayAmount(dayOfWeek, amountByWeekday.getOrDefault(dayOfWeek, 0)))
                .toList();
    }

    /**
     * pouchInsight가 문장을 고를 때 보는 사실들. 응답에 이미 들어간 집계 3종을 그대로 넘긴다
     * — 문구가 자기만의 집계를 다시 돌리면 도넛에 70%로 그려놓고 문구는 69%라고 말하는 일이 생긴다.
     * 지출이 하나도 없는 기간은 지목할 대상 자체가 없어 null을 넘기고, 그때 뭐라고 말할지는 Writer가 정한다.
     */
    private static ExpenseInsightWriter.PeriodFacts periodFacts(
            List<Expense> expenses, LocalDate periodStart, LocalDate periodEnd, int totalAmount,
            List<CategoryAmount> categoryBreakdown, List<EmotionAmount> emotionBreakdown,
            List<WeekdayAmount> weekdayBreakdown) {

        if (totalAmount == 0) {
            return null;
        }

        // 기간을 반으로 가르는 규칙은 Writer에만 둔다 — 여기서 또 계산하면 경계가 하루 어긋나도 아무도 모른다
        LocalDate firstHalfEnd = ExpenseInsightWriter.firstHalfEnd(periodStart, periodEnd);
        int firstHalfAmount = 0;
        int secondHalfAmount = 0;
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
                firstHalfAmount, secondHalfAmount);
    }

    /**
     * 1위 카테고리 안에서만 집계한 1위 이유.
     * 기간 전체 1위 이유를 가져다 쓰면 1위 지출 요인 카테고리인 카페와 무관한
     * 스트레스 지출로 만들어질 수 있다 — 두 절이 한 문장으로 묶여 있으므로 산정 범위도 같아야 한다.
     */
    private static ExpenseEmotion topEmotionWithin(List<Expense> expenses, ExpenseCategory category) {
        Map<ExpenseEmotion, Integer> amountByEmotion = new EnumMap<>(ExpenseEmotion.class);
        for (Expense expense : expenses) {
            if (expense.getCategory() == category) {
                amountByEmotion.merge(expense.getEmotion(), expense.getPrice(), Integer::sum);
            }
        }

        ExpenseEmotion topEmotion = null;
        int topAmount = 0;
        for (ExpenseEmotion emotion : ExpenseEmotion.values()) { // enum 선언 순서가 곧 동률 tie-breaker
            int amount = amountByEmotion.getOrDefault(emotion, 0);
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

    private static int sumPrice(List<Expense> expenses) {
        return expenses.stream().mapToInt(Expense::getPrice).sum();
    }

    /**
     * 정수 퍼센트. 분모는 항상 기간 총액이다
     * 반올림 때문에 조각들의 합이 99나 101이 될 수 있으므로 그래서 도넛 각도는 amount로 그려야 한다
     * 지출이 하나도 없는 기간은 분모가 0이라 나눌 수 없으므로 0%로 둔다.
     */
    private static int percentOf(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }

    /**
     * 지난달 대비 증감률(정수 %, 음수 허용). 지난달이 0원이면 증감률이 정의되지 않으므로 null을 반환하고,
     * 응답 record의 @JsonInclude(NON_NULL)이 키 자체를 생략
     */
    private static Integer diffRate(int currentAmount, int previousAmount) {
        if (previousAmount == 0) {
            return null;
        }
        return (int) Math.round((currentAmount - previousAmount) * 100.0 / previousAmount);
    }
}
