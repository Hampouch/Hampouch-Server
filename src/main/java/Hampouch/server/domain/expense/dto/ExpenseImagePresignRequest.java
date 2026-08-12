package Hampouch.server.domain.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 지출 이미지 presigned URL 발급 요청(POST /expenses/photos/presigned, POST /expenses/{expenseId}/photos/presigned 공용).
 * 지출 이미지는 한 장만 첨부, community처럼 List<FileInfo> 배치로 감싸지 않고 단건 필드로 둔다.
 * size는 contentLength 미검증 시 업로드 크기 무제한 문제를 재현하지 않기 위해 추가함
 */
public record ExpenseImagePresignRequest(

        @NotBlank(message = "파일 형식은 필수입니다.")
        @Pattern(regexp = "image/(jpeg|png|jpg|webp)", message = "지원하지 않는 이미지 형식입니다.")
        String contentType,

        @NotNull(message = "파일 크기는 필수입니다.")
        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long size
) {
}
