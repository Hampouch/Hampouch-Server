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

@Slf4j
@Component
public class GoogleVerifier implements SocialTokenVerifier {

    private final GoogleIdTokenVerifier verifier;

    public GoogleVerifier(@Value("${oauth.google.client-id}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    @Override
    public boolean supports(String provider) {
        return AuthProvider.GOOGLE.name().equals(provider);
    }

    @Override
    public SocialUserInfo verify(String providerToken) {
        try {
            GoogleIdToken idToken = verifier.verify(providerToken);

            if (idToken == null) {
                throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
            }

            GoogleIdToken.Payload payload = idToken.getPayload();

            String email = payload.getEmail();
            String providerId = payload.getSubject();
            String nickname = (String) payload.get("name");
            String profileImageUrl = (String) payload.get("picture");

            return new SocialUserInfo(email, providerId, nickname, profileImageUrl);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            log.error("구글 토큰 검증 실패", e);
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_TOKEN_INVALID);
        }
    }
}