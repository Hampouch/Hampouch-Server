package Hampouch.server.domain.community.dto.response;

public record AuthorResponse(
        Long userId,
        String authorName,
        String profileImageUrl
) {
    public static AuthorResponse of(Long userId, String authorName, String profileImageUrl) {
        return new AuthorResponse(userId, authorName, profileImageUrl);
    }
}