package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
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
        String customEmotion, // ETC일 때만 값 존재
        String memo, // detail이 없으면 null
        String imageUrl // detail 없으면 null
) {
    /**
     * customCategory/customEmotion은 엔티티의 문자열 컬럼 그대로 — ETC가 아니면 null이라 그대로 넘기면 됨.
     * detail은 memo/이미지가 하나도 없는 지출이면 null로 들어오고, 그 경우 memo는 null로 응답한다.
     * imageUrl은 엔티티에 저장된 값이 아니라 호출부(ExpenseService.getDetail())가 조회 시점에
     * ExpenseImageService.presignGetUrl()로 새로 발급해 넘겨준 값을 그대로 싣는다.
     */
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
