package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.ExpenseEmotion;

/**
 * Challenge 도메인이 결과 화면의 소비 감정 분석 결과.
 * ExpenseSpendingQuery.periodSpending()이 challenge에 진입점을 제공하기 위해 사용하는 dto
 * - emotion은 Enum 형태로 저장되어 있으므로 data의 정확성을 위해 Enum type으로 Entity 설정과 통일
 * - ratio가 정수 퍼센트면 반올림 때문에 합이 99나 101이 될 수 있다. 도넛 각도를 ratio로 누적해 그리면
 *    마지막 조각이 모자라거나 넘치는데, 지금 EmotionRatio에는 amount가 없어 그 회피로(각도는 amount로 계산)를
 *    쓸 수가 없다. ratio의 분모는 그 챌린지 기간의 총 지출액
 */
public record EmotionSpending(
        ExpenseEmotion emotion,
        long amount,
        int ratio
) {
}
