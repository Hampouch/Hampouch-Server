package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;

public record PostListResponse(
        Long postId,
        String postType,
        String category,
        String title,
        String content,
        String thumbnailUrl,
        String authorName,
        LocalDateTime createdAt,
        int viewCount,
        int likeCount,
        int commentCount,
        boolean isLiked,
        boolean isBookmarked,
        boolean isMine
) {
    public static PostListResponse of(
            Long postId, String postType, String category, String title, String content,
            String thumbnailUrl, String authorName, LocalDateTime createdAt,
            int viewCount, int likeCount, int commentCount,
            boolean isLiked, boolean isBookmarked, boolean isMine
    ) {
        return new PostListResponse(postId, postType, category, title, content, thumbnailUrl,
                authorName, createdAt, viewCount, likeCount, commentCount, isLiked, isBookmarked, isMine);
    }
}