package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;

import java.time.LocalDate;

/**
 * 지출 상세 조회 응답(GET /expenses/{expenseId}).
 * 필드명은 엔티티 내부 명명(expenseDate 등 예약어 회피용 이름)이 아니라 API 명세(date/category/emotion)를 그대로 따름 —
 * 엔티티 컬럼명과 DTO/JSON 필드명은 별개라는 원칙.
 */
public record ExpenseDetailResponse(
        Long expenseId,
        String name,
        int price,
        LocalDate date,
        ExpenseCategory category,
        String customCategory, // ETC일 때만 값 존재 — null이면 커스텀 태그 미사용
        ExpenseEmotion emotion,
        String customEmotion   // ETC일 때만 값 존재
) {
    /** customCategory/customEmotion은 엔티티의 문자열 컬럼 그대로(이슈 #61) — ETC가 아니면 null이라 그대로 넘기면 됨 */
    public static ExpenseDetailResponse from(Expense expense) {
        return new ExpenseDetailResponse(
                expense.getId(),
                expense.getName(),
                expense.getPrice(),
                expense.getExpenseDate(),
                expense.getCategory(),
                expense.getCustomCategory(),
                expense.getEmotion(),
                expense.getCustomEmotion()
        );
    }
}
