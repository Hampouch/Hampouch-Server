package Hampouch.server.domain.expense.repository;

/**
 * ExpenseRepository.sumTodayAndTotalByBattleIds() 전용 JPQL 생성자 표현식 DTO.
 * BattleParticipantSpending과 다른 점은 battleId를 함께 들고 다닌다는 것 — 여러 배틀을 한 번에
 * 집계할 때(GET /battles의 ONGOING 카드들) 어느 배틀의 today/total인지 구분할 유일한 방법이라
 * 필드로 얹었다
 */
public record BattleParticipantBattleSpending(Long battleId, Long userId, long todayAmount, long totalAmount) {
}
