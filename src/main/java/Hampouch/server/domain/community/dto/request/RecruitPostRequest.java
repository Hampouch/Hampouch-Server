package Hampouch.server.domain.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecruitPostRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content,

        @NotBlank(message = "햄배틀 초대 URL은 필수입니다.")
        @Size(max = 500, message = "햄배틀 초대 URL은 최대 500자까지 입력할 수 있습니다.")
        String battleUrl
) {
}