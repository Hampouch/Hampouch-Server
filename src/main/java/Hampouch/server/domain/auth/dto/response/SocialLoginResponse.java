package Hampouch.server.domain.auth.dto.response;

public record SocialLoginResponse(
        boolean isNewUser,
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInMs,
        long refreshTokenExpiresInMs,
        LoginResponse.UserSummary user
) {
    public static SocialLoginResponse of(
            boolean isNewUser,
            String accessToken,
            String refreshToken,
            long accessTokenExpiresInMs,
            long refreshTokenExpiresInMs,
            LoginResponse.UserSummary user
    ) {
        return new SocialLoginResponse(isNewUser, accessToken, refreshToken, "Bearer", accessTokenExpiresInMs, refreshTokenExpiresInMs, user);
    }
}