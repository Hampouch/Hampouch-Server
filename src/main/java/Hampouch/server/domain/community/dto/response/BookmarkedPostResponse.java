package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;

public record BookmarkedPostResponse(
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
        boolean isMine,
        LocalDateTime bookmarkedAt
) {
    public static BookmarkedPostResponse of(
            Long postId, String postType, String category, String title, String content,
            String thumbnailUrl, String authorName, LocalDateTime createdAt,
            int viewCount, int likeCount, int commentCount,
            boolean isLiked, boolean isBookmarked, boolean isMine, LocalDateTime bookmarkedAt
    ) {
        return new BookmarkedPostResponse(postId, postType, category, title, content, thumbnailUrl,
                authorName, createdAt, viewCount, likeCount, commentCount, isLiked, isBookmarked, isMine, bookmarkedAt);
    }
}