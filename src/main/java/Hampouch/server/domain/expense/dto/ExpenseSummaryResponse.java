package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * GET /expenses/summary/week, /expenses/summary/month 공용 응답
 */
public record ExpenseSummaryResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        long totalAmount,
        long dailyAverage,
        List<DailyAmount> dailyBreakdown
) {

    /** 지출이 있던 날짜만 담기는 한 줄 — sumGroupedByDate()가 이미 GROUP BY로 지출 없는 날짜를 뺀 채로 넘겨준다. */
    public record DailyAmount(LocalDate date, long totalAmount) {
        private static DailyAmount from(ExpenseDailyTotal daily) {
            return new DailyAmount(daily.date(), daily.totalAmount());
        }
    }

    /**
     * dailyAverage = totalAmount / (경과일수) - 오늘까지 경과한 일수 기준 -> 미래는 어차피 0원이므로
     * - today가 periodEnd 이후일 경우 경과일수 = 기간 전체 일수, 아니라면 경과일수 계산 필요
     * - 아직 시작 안 한 미래 기간이면 totalAmount가 항상 0 - 앱에서는 미래시점 조회를 가급적 하지 않겠지만 로직상의 방어
     */
    public static ExpenseSummaryResponse of(LocalDate periodStart, LocalDate periodEnd,
                                             List<ExpenseDailyTotal> dailyTotals, LocalDate today) {
        long totalAmount = dailyTotals
                .stream()
                .mapToLong(ExpenseDailyTotal::totalAmount)
                .sum();

        long dailyAverage = 0;
        if (totalAmount > 0) {
            LocalDate elapsedUntil = today.isBefore(periodEnd) ? today : periodEnd;
            long elapsedDays = ChronoUnit.DAYS.between(periodStart, elapsedUntil) + 1;
            dailyAverage = totalAmount / elapsedDays;
        }

        List<DailyAmount> dailyBreakdown = dailyTotals.stream().map(DailyAmount::from).toList();
        return new ExpenseSummaryResponse(periodStart, periodEnd, totalAmount, dailyAverage, dailyBreakdown);
    }
}
