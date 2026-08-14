package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;

import java.time.LocalDate;

public record ExpenseDetailResponse(
        Long expenseId,
        String name,
        int price,
        LocalDate date,
        ExpenseCategory category,
        String customCategory, // ETC일 때만 값 존재 — null이면 커스텀 태그 미사용
        ExpenseEmotion emotion,
        String customEmotion, // ETC일 때만 값 존재
        String memo, // detail이 없으면 null
        String imageUrl // detail 없으면 null
) {
    /** detail이 없으면 memo는 null. imageUrl은 저장값이 아니라 호출부가 조회 시점에 새로 서명해 넘긴 값. */
    public static ExpenseDetailResponse from(Expense expense, ExpenseDetail detail, String imageUrl) {
        return new ExpenseDetailResponse(
                expense.getId(),
                expense.getName(),
                expense.getPrice(),
                expense.getExpenseDate(),
                expense.getCategory(),
                expense.getCustomCategory(),
                expense.getEmotion(),
                expense.getCustomEmotion(),
                detail != null ? detail.getMemo() : null,
                imageUrl
        );
    }
}
