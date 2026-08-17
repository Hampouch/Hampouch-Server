package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record CommentResponse(
        Long commentId,
        Long userId,
        String authorName,
        String profileImageUrl,
        String content,
        boolean isDeleted,
        boolean isMine,
        LocalDateTime createdAt,
        List<ReplyResponse> replies,
        long replyCount,
        boolean hasMoreReplies
) {
    public static CommentResponse of(
            Long commentId,
            Long userId,
            String authorName,
            String profileImageUrl,
            String content,
            boolean isDeleted,
            boolean isMine,
            LocalDateTime createdAt,
            List<ReplyResponse> replies,
            long replyCount,
            boolean hasMoreReplies
    ) {
        return new CommentResponse(
                commentId,
                userId,
                authorName,
                profileImageUrl,
                content,
                isDeleted,
                isMine,
                createdAt,
                replies,
                replyCount,
                hasMoreReplies
        );
    }
}