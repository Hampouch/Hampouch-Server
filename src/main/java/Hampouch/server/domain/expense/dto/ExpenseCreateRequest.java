package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

/**
 * 지출 생성/수정 공용 요청(POST /expenses, PUT /expenses/{id}) — 필드 구성이 동일해서 별도 UpdateRequest를 만들지 않고 병합
 */
public record ExpenseCreateRequest(

        @NotBlank
        @Size(max = 90)
        String name,

        @NotNull
        @Min(1) // 단건 금액이라 0 이하 불가 — budgetTotal(@Min(1))과 동일 성격, spentAmount(합계, @Min(0))와는 다름)
        Integer price,

        @NotNull
        ExpenseCategory category,

        @Size(max = 50)
        String customCategory, // category=ETC일 때만 사용하는 자유 입력 태그 — 그 외엔 null이어야 함(아래 isCategoryConsistent로 검증)

        @NotNull
        ExpenseEmotion emotion,

        @Size(max = 50)
        String customEmotion, // emotion=ETC일 때만 사용 — 위와 동일한 이유로 nullable

        @NotNull @PastOrPresent // 미래 날짜의 지출 입력 방지 — 오늘까지만 허용
        LocalDate date
) {

    /** category와 customCategory의 존재 여부가 항상 같아야 함(둘 다 있거나 둘 다 없거나) — XOR 관계를 단일 == 비교로 표현 */
    @AssertTrue(message = "category가 ETC일 때만 customCategory를 입력할 수 있습니다.")
    public boolean isCategoryConsistent() {
        boolean hasCustomCategory = customCategory != null && !customCategory.isBlank();
        return (category == ExpenseCategory.ETC) == hasCustomCategory;
    }

    @AssertTrue(message = "emotion이 ETC일 때만 customEmotion을 입력할 수 있습니다.")
    public boolean isEmotionConsistent() {
        boolean hasCustomEmotion = customEmotion != null && !customEmotion.isBlank();
        return (emotion == ExpenseEmotion.ETC) == hasCustomEmotion;
    }

}
