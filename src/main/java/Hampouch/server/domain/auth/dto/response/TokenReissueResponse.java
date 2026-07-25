package Hampouch.server.domain.auth.dto.response;

public record TokenReissueResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInMs,
        long refreshTokenExpiresInMs
) {
    public static TokenReissueResponse of(
            String accessToken,
            String refreshToken,
            long accessTokenExpiresInMs,
            long refreshTokenExpiresInMs
    ) {
        return new TokenReissueResponse(accessToken, refreshToken, "Bearer", accessTokenExpiresInMs, refreshTokenExpiresInMs);
    }
}