package Hampouch.server.domain.community.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record PostDetailResponse(
        Long postId,
        String postType,
        String category,
        String title,
        String content,
        AuthorResponse author,
        FoodDetailResponse foodDetail,       // FOOD_RECOMMEND일 때만 채워짐, 아니면 null
        RecruitDetailResponse recruitDetail, // RECRUIT일 때만 채워짐, 아니면 null
        List<PostImageResponse> images,
        int viewCount,
        int likeCount,
        int commentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean isLiked,
        boolean isBookmarked,
        boolean isMine,
        PageResponse<CommentResponse> comments // 최상위 댓글 페이지네이션
) {
    public static PostDetailResponse of(
            Long postId, String postType, String category, String title, String content,
            AuthorResponse author, FoodDetailResponse foodDetail, RecruitDetailResponse recruitDetail,
            List<PostImageResponse> images, int viewCount, int likeCount, int commentCount,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            boolean isLiked, boolean isBookmarked, boolean isMine, PageResponse<CommentResponse> comments
    ) {
        return new PostDetailResponse(postId, postType, category, title, content, author, foodDetail,
                recruitDetail, images, viewCount, likeCount, commentCount, createdAt, updatedAt,
                isLiked, isBookmarked, isMine, comments);
    }
}