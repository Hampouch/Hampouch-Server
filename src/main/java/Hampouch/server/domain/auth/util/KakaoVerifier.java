package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Component
public class KakaoVerifier implements SocialTokenVerifier {

    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;

    public KakaoVerifier() {
        this.restClient = RestClient.create();
    }

    @Override
    public boolean supports(String provider) {
        return AuthProvider.KAKAO.name().equals(provider);
    }

    @SuppressWarnings("unchecked")
    @Override
    public SocialUserInfo verify(String providerToken) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(USER_INFO_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + providerToken)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
            }

            String providerId = String.valueOf(response.get("id"));

            Map<String, Object> kakaoAccount = (Map<String, Object>) response.get("kakao_account");
            String email = kakaoAccount != null ? (String) kakaoAccount.get("email") : null;

            Map<String, Object> properties = (Map<String, Object>) response.get("properties");
            String nickname = properties != null ? (String) properties.get("nickname") : null;
            String profileImageUrl = properties != null ? (String) properties.get("profile_image") : null;

            return new SocialUserInfo(email, providerId, nickname, profileImageUrl);
        } catch (RestClientException e) {
            log.error("카카오 토큰 검증 실패", e);
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
        }
    }
}