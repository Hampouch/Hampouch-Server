package Hampouch.server.domain.community.dto.response;

public record PostLikeToggleResponse(
        Long postId,
        boolean isLiked,
        int likeCount
) {
    public static PostLikeToggleResponse of(Long postId, boolean isLiked, int likeCount) {
        return new PostLikeToggleResponse(postId, isLiked, likeCount);
    }
}