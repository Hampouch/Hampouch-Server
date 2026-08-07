package Hampouch.server.domain.auth.dto.response;

public record NicknameCheckResponse(
        String nickname,
        boolean available
) {
    public static NicknameCheckResponse of(String nickname, boolean available) {
        return new NicknameCheckResponse(nickname, available);
    }
}