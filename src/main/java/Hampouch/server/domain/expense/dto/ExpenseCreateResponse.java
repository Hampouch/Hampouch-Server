package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;

/**
 * 생성/수정 응답 — expenseId만 반환. PUT 응답도 이 DTO를 그대로 재사용(별도 UpdateResponse 불필요, CreateChallengeResponse 패턴과 동일).
 */
public record ExpenseCreateResponse(
        Long expenseId
) {
    public static ExpenseCreateResponse from(Expense expense) {
        return new ExpenseCreateResponse(expense.getId());
    }
}
