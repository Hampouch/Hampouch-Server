package Hampouch.server.domain.challenge.service;

/**
 * 기록된 일자들로부터 계산한 집계 요약 (저장하지 않고 조회 시 계산).
 * 서비스 내부 계산용 (dto 아님) — API로 나갈 땐 ResultResponse.Summary로 옮겨 담음.
 *
 * @param successDays 성공한 날 수
 * @param overDays    초과한 날 수
 * @param savedAmount 총 절약 = Σ max(0, dailyLimit − spent)
 * @param overAmount  초과 금액 = Σ max(0, spent − dailyLimit)
 * @param maxStreak   최장 연속 성공일 수
 * @param actualSpent 실제 사용 합계 = Σ spent
 */
public record ChallengeSummary(
        int successDays,
        int overDays,
        long savedAmount,
        long overAmount,
        int maxStreak,
        long actualSpent
) {
}
