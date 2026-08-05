package Hampouch.server.domain.auth.dto.response;

public record AuthMeResponse(
        Long userId,
        String nickname,
        boolean needsNickname,
        String role,
        String status
) {
    public static AuthMeResponse of(
            Long userId,
            String nickname,
            boolean needsNickname,
            String role,
            String status
    ) {
        return new AuthMeResponse(userId, nickname, needsNickname, role, status);
    }
}