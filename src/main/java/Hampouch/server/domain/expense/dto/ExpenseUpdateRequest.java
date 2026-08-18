package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record ExpenseUpdateRequest(

        @Size(max = 90)
        String name,

        @NotNull
        @Min(0)
        @Max(Expense.PRICE_MAX)
        Integer price,

        ExpenseCategory category,

        @Size(max = 50)
        String customCategory,

        ExpenseEmotion emotion,

        @Size(max = 50)
        String customEmotion,

        @NotNull @PastOrPresent
        LocalDate date,

        @Size(max = 300)
        String memo
) {

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