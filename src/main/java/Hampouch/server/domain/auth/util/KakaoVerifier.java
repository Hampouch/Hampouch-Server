package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 클라이어트가 카카오 SDK로 로그인하면 access token을 받음
 * 해당 token으로 카카오 사용자 정보 조회 API를 호출해서, 사용자 인증과 사용자 정보 수집를 동시에 한다
 */

@Slf4j
@Component
public class KakaoVerifier implements SocialTokenVerifier {

    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";
    //연결/응답 대기 타임아웃
    private static final int TIMEOUT_MS = 5000;

    private final RestClient restClient;

    public KakaoVerifier() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT_MS);
        requestFactory.setReadTimeout(TIMEOUT_MS);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public boolean supports(String provider) {
        return AuthProvider.KAKAO.name().equals(provider);
    }

    @SuppressWarnings("unchecked") //카카오 API 응답이 중첩된 JSON이라 Map으로 받으면 캐스팅이 불가피(경고 피하게)
    @Override
    public SocialUserInfo verify(String providerToken) {
        try {
            //카카오 사용자 정보 조회 API 호출
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
            String email = extractVerifiedEmail(kakaoAccount);

            return new SocialUserInfo(email, providerId);
        } catch (RestClientException e) {
            log.error("카카오 토큰 검증 실패", e);
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
        }
    }

    //인증된 이메일만 신뢰하도록
    private String extractVerifiedEmail(Map<String, Object> kakaoAccount) {
        if (kakaoAccount == null) {
            return null;
        }
        boolean isVerified = Boolean.TRUE.equals(kakaoAccount.get("is_email_verified"));
        return isVerified ? (String) kakaoAccount.get("email") : null;
    }
}