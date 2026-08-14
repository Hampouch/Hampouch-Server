package Hampouch.server.domain.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PATCH /expenses/{expenseId}/photos 요청. 형식 오류는 @Pattern으로, 미업로드는 HeadObject로 구분(두 에러 케이스 대응). */
public record ExpenseImageAttachRequest(

        @NotBlank(message = "imageKey는 필수입니다.")
        @Pattern(regexp = "^expenses/\\d+/[A-Za-z0-9\\-]+\\.(jpg|png|webp)$", message = "올바른 이미지 key 형식이 아닙니다.")
        String imageKey
) {
}
