package Hampouch.server.domain.auth.dto.response;

public record EmailSendResponse(
        long expiresInSeconds
) {
    public static EmailSendResponse of(long expiresInSeconds) {
        return new EmailSendResponse(expiresInSeconds);
    }
}