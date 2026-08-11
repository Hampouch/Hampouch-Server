package Hampouch.server.domain.expense.repository;

/**
 * ExpenseRepository.sumTodayAndTotalByUsers() 전용 JPQL 생성자 표현식 DTO.
 * battle 도메인이 소비하지만 Expense가 홈인 쿼리라 ExpenseDailyTotal과 같은 패키지에 둔다
 * (Battle은 battle_id를 안 갖고 있어 battle→expense 연결은 참가자 user + 기간 교집합으로만
 * 가능 — 그 연결을 만드는 쪽은 항상 Expense 리포지토리여야 한다는 원칙).
 * SUM()은 JPQL에서 Long으로 집계되므로 필드 타입도 long(ExpenseDailyTotal과 동일 컨벤션).
 */
public record BattleParticipantSpending(Long userId, long todayAmount, long totalAmount) {
}
