package Hampouch.server.domain.user.dto.response;

public record NicknameUpdateResponse(
        String nickname
) {
    public static NicknameUpdateResponse of(String nickname) {
        return new NicknameUpdateResponse(nickname);
    }
}
