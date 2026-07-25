package Hampouch.server.domain.auth.dto.response;

public record SignupResponse(
        Long userId,
        String email,
        String nickname,
        String provider
) {
    public static SignupResponse of(Long userId, String email, String nickname, String provider) {
        return new SignupResponse(userId, email, nickname, provider);
    }
}