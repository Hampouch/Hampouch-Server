package Hampouch.server.domain.expense.repository;

import java.time.LocalDate;

/**
 * ExpenseRepository.sumGroupedByDate() 전용 JPQL 생성자 표현식 DTO
 * SUM(e.price)는 JPQL에서 Long으로 집계되므로 필드 타입도 long으로 설정
 */
public record ExpenseDailyTotal(LocalDate date, long totalAmount) {
}
