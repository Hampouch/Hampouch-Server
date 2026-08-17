package Hampouch.server.domain.community.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CommentCreateRequest(

        Long parentCommentId,

        @NotBlank(message = "댓글 내용은 필수입니다.")
        String content
) {
}