package Hampouch.server.domain.auth.dto.response;

public record EmailVerifyResponse(
        String email,
        String purpose,
        boolean verified
) {
    public static EmailVerifyResponse of(String email, String purpose, boolean verified) {
        return new EmailVerifyResponse(email, purpose, verified);
    }
}