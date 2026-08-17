package Hampouch.server.domain.community.dto.response;

public record PostBookmarkToggleResponse(
        Long postId,
        boolean isBookmarked
) {
    public static PostBookmarkToggleResponse of(Long postId, boolean isBookmarked) {
        return new PostBookmarkToggleResponse(postId, isBookmarked);
    }
}