package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

/** 지출 생성 요청(POST /expenses) — name/category/emotion은 선택, price/date만 필수. */
public record ExpenseCreateRequest(

        @Size(max = 90)
        String name,

        @NotNull
        @Min(0)
        @Max(Expense.PRICE_MAX)
        Integer price,

        ExpenseCategory category,

        @Size(max = 50)
        String customCategory, // category = ETC일 때 외엔 null

        ExpenseEmotion emotion,

        @Size(max = 50)
        String customEmotion, // emotion = ETC일 때 외엔 null

        @NotNull @PastOrPresent
        LocalDate date,

        @Size(max = 300)
        String memo,

        @Pattern(regexp = "^expenses/\\d+/[A-Za-z0-9\\-]+\\.(jpg|png|webp)$", message = "올바른 이미지 key 형식이 아닙니다.")
        String imageKey
) {

    /** category=ETC일 때만 customCategory가 있어야 함(둘 다 있거나 둘 다 없거나). */
    @AssertTrue(message = "category가 ETC일 때만 customCategory를 입력할 수 있습니다.")
    public boolean isCategoryConsistent() {
        boolean hasCustomCategory = customCategory != null && !customCategory.isBlank();
        return (category == ExpenseCategory.ETC) == hasCustomCategory;
    }

    /** customCategory와 대칭 — emotion=ETC일 때만 customEmotion 존재. */
    @AssertTrue(message = "emotion이 ETC일 때만 customEmotion을 입력할 수 있습니다.")
    public boolean isEmotionConsistent() {
        boolean hasCustomEmotion = customEmotion != null && !customEmotion.isBlank();
        return (emotion == ExpenseEmotion.ETC) == hasCustomEmotion;
    }

}
