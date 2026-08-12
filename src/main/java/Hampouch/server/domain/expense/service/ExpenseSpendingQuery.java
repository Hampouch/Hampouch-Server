package Hampouch.server.domain.expense.service;

import java.time.LocalDate;

/**
 * Challenge 도메인이 결과 화면의 소비 감정 분석 그래프·총 지출액을 그릴 때 쓰는 지출 집계 진입점
 * Expense domain에 해당하는 내용이지만 해당 domain의 전용 errorCode 도출을 막기 위해 Interface 활용
 */
public interface ExpenseSpendingQuery {

    /**
     * userId의 [periodStart, periodEnd] 기간 지출을 이유별로 집계
     * 챌린지 결과 화면 전용 — 호출자가 이미 유효한 기간만 넘긴다는 전제,
     * null / 기간 역전 / 100일 초과는 CustomException이 아니라 NullPointerException/IllegalArgumentException으로
     * 던진다. 시작일이 미래면 예외 대신 빈 집계를 반환한다
     */
    PeriodSpending periodSpending(Long userId, LocalDate periodStart, LocalDate periodEnd);
}
