package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;

public record ReplyResponse(
        Long commentId,
        Long userId,
        String authorName,
        String profileImageUrl,
        String content,
        boolean isDeleted,
        boolean isMine,
        LocalDateTime createdAt
) {
    public static ReplyResponse of(
            Long commentId, Long userId, String authorName, String profileImageUrl,
            String content, boolean isDeleted, boolean isMine, LocalDateTime createdAt
    ) {
        return new ReplyResponse(commentId, userId, authorName, profileImageUrl, content,
                isDeleted, isMine, createdAt);
    }
}