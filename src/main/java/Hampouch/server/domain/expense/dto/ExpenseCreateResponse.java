package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;

/**
 * PUT /expenses/{expenseId} 공통 활용 DTO
 */
public record ExpenseCreateResponse(
        Long expenseId
) {
    public static ExpenseCreateResponse from(Expense expense) {
        return new ExpenseCreateResponse(expense.getId());
    }
}
