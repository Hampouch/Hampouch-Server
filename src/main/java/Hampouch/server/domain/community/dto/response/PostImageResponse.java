package Hampouch.server.domain.community.dto.response;

public record PostImageResponse(
        String imageKey,
        String imageUrl
) {
    public static PostImageResponse of(String imageKey, String imageUrl) {
        return new PostImageResponse(imageKey, imageUrl);
    }
}