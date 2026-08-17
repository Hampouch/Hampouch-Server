package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;

public record CommentCreateResponse(
        Long commentId,
        Long postId,
        Long parentCommentId,
        String content,
        LocalDateTime createdAt
) {
    public static CommentCreateResponse from(
            Long commentId,
            Long postId,
            Long parentCommentId,
            String content,
            LocalDateTime createdAt
    ) {
        return new CommentCreateResponse(
                commentId,
                postId,
                parentCommentId,
                content,
                createdAt
        );
    }
}