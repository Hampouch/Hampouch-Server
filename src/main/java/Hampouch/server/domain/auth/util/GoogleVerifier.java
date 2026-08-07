package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * 클라이언트가 구글 SDK로 로그인하면 ID token이라는 JWT를 받음
 * 서버는 그 JWT의 서명이 구글에서 발급된 진짜 서명인지 검증
 */

@Slf4j
@Component
public class GoogleVerifier implements SocialTokenVerifier {

    private final GoogleIdTokenVerifier verifier;

    //hampouch를 위해 발급된 JWT인지 확인
    public GoogleVerifier(@Value("${oauth.google.client-id}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    @Override
    public boolean supports(String provider) {
        return AuthProvider.GOOGLE.name().equals(provider);
    }

    //JWT 실제 검증
    @Override
    public SocialUserInfo verify(String providerToken) {
        try {
            GoogleIdToken idToken = verifier.verify(providerToken);

            if (idToken == null) {
                throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
            }

            GoogleIdToken.Payload payload = idToken.getPayload();

            //해당 이메일이 인증된 것인지 확인
            Boolean emailVerified = payload.getEmailVerified();
            String email = Boolean.TRUE.equals(emailVerified) ? payload.getEmail() : null;

            String providerId = payload.getSubject();

            return new SocialUserInfo(email, providerId);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            log.error("구글 토큰 검증 실패", e);
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
        }
    }
}