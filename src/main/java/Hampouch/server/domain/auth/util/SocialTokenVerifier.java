package Hampouch.server.domain.auth.util;

public interface SocialTokenVerifier {

    boolean supports(String provider);

    // 검증 성공 시 소셜 계정의 email/providerId를 담아 반환, 실패 시 CustomException(AUTH_SOCIAL_TOKEN_INVALID) 던짐
    SocialUserInfo verify(String providerToken);

    record SocialUserInfo(String email, String providerId, String nickname, String profileImageUrl) {}
}