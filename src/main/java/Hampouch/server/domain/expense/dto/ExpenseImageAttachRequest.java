package Hampouch.server.domain.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /expenses/{expenseId}/photos 요청. imageKey 형식 자체는 여기서 @Pattern으로 걸러 VALIDATION_ERROR로
 * 빠지게 하고, 형식은 맞지만 실제 S3에 없는 키는 ExpenseImageService가 HeadObject로 확인해
 * EXPENSE_IMAGE_NOT_UPLOADED로 구분한다(명세의 두 에러 케이스와 대응).
 */
public record ExpenseImageAttachRequest(

        @NotBlank(message = "imageKey는 필수입니다.")
        @Pattern(regexp = "^expenses/\\d+/[A-Za-z0-9\\-]+\\.(jpg|png|webp)$", message = "올바른 이미지 key 형식이 아닙니다.")
        String imageKey
) {
}
