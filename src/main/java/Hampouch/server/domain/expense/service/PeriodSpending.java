package Hampouch.server.domain.expense.service;

import java.util.List;

/**
 * ExpenseSpendingQuery.periodSpending()의 결과 — 챌린지 결과 화면 소비 감정 분석 탭에서 한 번의 호출로 받아 쓴다.
 * totalAmount는 emotionBreakdown의 ratio 분모와 같은 값을 노출해 둔 것 — 응답과 문구가 다른 집계를 보지 않게 한다.
 */
public record PeriodSpending(
        int totalAmount,
        List<EmotionSpending> emotionBreakdown
) {
}
