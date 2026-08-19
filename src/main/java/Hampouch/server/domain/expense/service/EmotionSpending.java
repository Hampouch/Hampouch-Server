package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.ExpenseEmotion;

/**
 * 챌린지 결과 화면의 소비 감정 분석 한 조각. ExpenseSpendingQuery.periodSpending()이 돌려주는 값이다.
 * ratio는 정수 퍼센트라 반올림 탓에 합이 99나 101이 될 수 있으므로 도넛 각도는 amount로 그려야 한다.
 * 분모는 그 챌린지 기간의 총 지출액이다.
 */
public record EmotionSpending(
        ExpenseEmotion emotion,
        long amount,
        int ratio
) {
}
