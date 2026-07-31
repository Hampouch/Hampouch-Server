package Hampouch.server.domain.community.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PresignRequest(

        @NotEmpty(message = "이미지는 최소 1장 이상 등록해야 합니다.")
        @Size(max = 5, message = "이미지는 최대 5장까지 등록할 수 있습니다.")
        @Valid
        List<FileInfo> files
) {
    public record FileInfo(

            @NotBlank(message = "파일 형식은 필수입니다.")
            @Pattern(
                    regexp = "image/(jpeg|png|jpg|webp)",
                    message = "지원하지 않는 이미지 형식입니다."
            )
            String contentType,

            @NotNull(message = "파일 크기는 필수입니다.")
            @Positive(message = "파일 크기는 0보다 커야 합니다.")
            Long size
    ) {
    }
}