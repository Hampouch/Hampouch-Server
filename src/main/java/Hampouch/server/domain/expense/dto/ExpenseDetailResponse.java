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
 * memo/imageUrl은 API 명세(res.data.detail.memo/imageUrl)대로 최상위가 아니라 중첩된 detail 객체로 응답
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
        Detail detail // memo/이미지가 하나도 없어도 detail 객체 자체는 항상 내려가고, 내부 필드만 null
) {
    /**
     * detail을 통째로 null로 만들지 않고 항상 객체로 내려주되 내부 필드만 null로 두는 이유:
     * 프론트에서 detail이 있는지와 memo/이미지가 있는지를 별도로 null-check하지 않게 하기 위함
     */
    public record Detail(String memo, String imageUrl) {}

    /**
     * customCategory/customEmotion은 엔티티의 문자열 컬럼 그대로 — ETC가 아니면 null이라 그대로 넘기면 됨.
     * detailEntity는 memo/이미지가 하나도 없는 지출이면 null로 들어오고(ExpenseService.getDetail() 참고),
     * 그 경우 Detail(null, null)로 응답한다.
     */
    public static ExpenseDetailResponse from(Expense expense, ExpenseDetail detailEntity) {
        return new ExpenseDetailResponse(
                expense.getId(),
                expense.getName(),
                expense.getPrice(),
                expense.getExpenseDate(),
                expense.getCategory(),
                expense.getCustomCategory(),
                expense.getEmotion(),
                expense.getCustomEmotion(),
                new Detail(
                        detailEntity != null ? detailEntity.getMemo() : null,
                        detailEntity != null ? detailEntity.getImageUrl() : null
                )
        );
    }
}
