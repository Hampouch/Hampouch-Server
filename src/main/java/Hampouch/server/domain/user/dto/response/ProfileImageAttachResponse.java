package Hampouch.server.domain.user.dto.response;

public record ProfileImageAttachResponse(
        String imageUrl
) {
    public static ProfileImageAttachResponse of(String imageUrl) {
        return new ProfileImageAttachResponse(imageUrl);
    }
}
