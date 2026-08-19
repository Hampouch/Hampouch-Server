package Hampouch.server.domain.expense.service;

import java.time.LocalDate;

/**
 * Challenge 도메인이 결과 화면의 감정 분석 그래프·총 지출액을 그릴 때 쓰는 집계 진입점.
 * Expense 전용 errorCode가 Challenge 쪽으로 새지 않도록 인터페이스로 좁혀 둔다.
 */
public interface ExpenseSpendingQuery {

    /**
     * userId의 [periodStart, periodEnd] 지출을 이유별로 집계. 호출자가 유효한 기간만 넘긴다는 전제라
     * null / 기간 역전 / 100일 초과는 CustomException이 아니라 NPE·IAE로 던진다.
     * 시작일이 미래면 예외 대신 빈 집계를 돌려준다.
     */
    PeriodSpending periodSpending(Long userId, LocalDate periodStart, LocalDate periodEnd);
}
