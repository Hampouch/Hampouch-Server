package Hampouch.server.domain.user.dto.response;

public record ProfileImagePresignResponse(
        String imageKey,
        String uploadUrl,
        long expiresInSeconds
) {
    public static ProfileImagePresignResponse of(String imageKey, String uploadUrl, long expiresInSeconds) {
        return new ProfileImagePresignResponse(imageKey, uploadUrl, expiresInSeconds);
    }
}
