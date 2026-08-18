package Hampouch.server.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** presigned URL 발급 요청. 이미지는 한 장만 첨부해 단건 필드로 둔다. size는 업로드 크기 무제한 방지용. */
public record ProfileImagePresignRequest(

        @NotBlank(message = "파일 형식은 필수입니다.")
        @Pattern(regexp = "image/(jpeg|png|jpg|webp)", message = "지원하지 않는 이미지 형식입니다.")
        String contentType,

        @NotNull(message = "파일 크기는 필수입니다.")
        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long size
) {
}
