package Hampouch.server.domain.user.dto.response;

public record UserMeResponse(
        String nickname,
        String profileImageUrl,
        String handle
) {
    public static UserMeResponse of(String nickname, String profileImageUrl, String email) {
        return new UserMeResponse(nickname, profileImageUrl, extractHandle(email));
    }

    private static String extractHandle(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
