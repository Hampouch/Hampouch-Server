package Hampouch.server.domain.expense.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.YearMonth;
import java.util.List;

/**
 * GET /expenses/analysis/trend 응답 — 최근 6개월 식비 추이. month는 창의 마지막 달이다.
 * YearMonth 직렬화 형식을 @JsonFormat으로 못박는다 — 기본 설정에 맡기면 설정이 바뀔 때
 * 응답이 배열로 조용히 변한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseTrendResponse(
        @JsonFormat(pattern = "yyyy-MM") YearMonth month,
        long totalAmount,
        long monthlyAverage,
        Integer diffRateFromLastMonth,
        List<MonthlyAmount> trend,
        String trendInsight
) {

    /** 항상 6개. 지출이 없는 달도 amount 0으로 채우고 오름차순(과거 -> 최근)으로 정렬한다. */
    public record MonthlyAmount(
            @JsonFormat(pattern = "yyyy-MM") YearMonth month,
            long amount
    ) {}
}
