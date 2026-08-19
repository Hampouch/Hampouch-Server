package Hampouch.server.domain.expense.repository;

/**
 * sumTodayAndTotalByBattleIds() 전용 JPQL 생성자 표현식 DTO.
 * 여러 배틀을 한 번에 집계할 때 어느 배틀의 today/total인지 구분할 방법이 battleId뿐이라 함께 들고 다닌다.
 */
public record BattleParticipantBattleSpending(Long battleId, Long userId, long todayAmount, long totalAmount) {
}
