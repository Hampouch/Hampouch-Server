package Hampouch.server.domain.challenge.dto;

/**
 * POST /api/challenges/{id}/adjust 응답.
 * dailyLimit은 유저가 정한 값이 아니라 budgetTotal에서 파생된 값인데도 같이 내리는 이유는,
 * 조정 화면이 고른 목표 금액 아래에 하루 식비 목표를 바로 보여 주기 때문이다(파생 규칙을 클라가 다시 구현하지 않게).
 * usedCount·maxCount도 같이 내려 조정 직후 홈을 다시 부르지 않고 남은 횟수를 갱신할 수 있게 한다.
 */
public record AdjustGoalResponse(
        Long challengeId,
        int budgetTotal,
        int dailyLimit,
        int usedCount,
        int maxCount
) {
}
