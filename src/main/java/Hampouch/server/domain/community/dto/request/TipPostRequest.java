package Hampouch.server.domain.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TipPostRequest(

        @NotBlank(message = "카테고리는 필수입니다.")
        String category,

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content,

        @Size(max = 5, message = "이미지는 최대 5장까지 등록할 수 있습니다.")
        List<
                @NotBlank(message = "이미지 key는 비어 있을 수 없습니다.")
                @Pattern(
                        regexp = "^community/posts/[^/]+\\.(jpg|png|webp)$",
                        message = "올바른 이미지 key 형식이 아닙니다."
                )
                        String
                > imageKeys
) {
}