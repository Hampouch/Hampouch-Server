package Hampouch.server.domain.auth.dto.response;

public record NicknameSetResponse(
        Long userId,
        String nickname
) {
    public static NicknameSetResponse of(Long userId, String nickname) {
        return new NicknameSetResponse(userId, nickname);
    }
}