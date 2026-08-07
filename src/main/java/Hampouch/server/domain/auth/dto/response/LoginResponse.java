package Hampouch.server.domain.auth.dto.response;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInMs,
        long refreshTokenExpiresInMs,
        UserSummary user
) {
    public static LoginResponse of(
            String accessToken,
            String refreshToken,
            long accessTokenExpiresInMs,
            long refreshTokenExpiresInMs,
            UserSummary user
    ) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", accessTokenExpiresInMs, refreshTokenExpiresInMs, user);
    }

    public record UserSummary(
            Long userId,
            String role,
            String status
    ) {
        public static UserSummary of(Long userId, String role, String status) {
            return new UserSummary(userId, role, status);
        }
    }
}