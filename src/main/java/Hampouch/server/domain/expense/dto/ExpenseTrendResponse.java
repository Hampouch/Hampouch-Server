package Hampouch.server.domain.expense.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.YearMonth;
import java.util.List;

/**
 * GET /expenses/analysis/trend 응답 — 최근 월별 식비 추이.
 * month는 6개월 창의 마지막 달을 지정 -> 6개월 고정인지 확인 필요
 * YearMonth를 2026-05로 직렬화하는 건 Spring Boot 기본 설정(WRITE_DATES_AS_TIMESTAMPS=false)에 의존해도 되지만,
 * 설정이 바뀌면 응답이 [2026,5] 배열로 조용히 변하는 자리라 @JsonFormat으로 계약을 명시해 둔다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseTrendResponse(
        @JsonFormat(pattern = "yyyy-MM") YearMonth month,
        int totalAmount,
        int monthlyAverage,
        Integer diffRateFromLastMonth,
        List<MonthlyAmount> trend,
        String trendInsight
) {

    /** 항상 6개. 지출이 없는 달도 amount 0으로 채우고 오름차순(과거 -> 최근)으로 정렬한다. */
    public record MonthlyAmount(
            @JsonFormat(pattern = "yyyy-MM") YearMonth month,
            int amount
    ) {}
}
