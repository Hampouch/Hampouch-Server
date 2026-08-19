package Hampouch.server.domain.expense.repository;

/**
 * sumTodayAndTotalByUsers() 전용 JPQL 생성자 표현식 DTO.
 * battle 도메인이 소비하지만 Expense가 홈인 쿼리라 여기 둔다 — 배틀과 지출은 참가자와 기간으로만
 * 이어지고 그 연결은 항상 Expense 리포지토리가 만든다.
 */
public record BattleParticipantSpending(Long userId, long todayAmount, long totalAmount) {
}
