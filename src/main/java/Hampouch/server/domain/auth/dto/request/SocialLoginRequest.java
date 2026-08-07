package Hampouch.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SocialLoginRequest(

        @NotBlank(message = "provider는 GOOGLE 또는 KAKAO만 허용됩니다.")
        @Pattern(regexp = "GOOGLE|KAKAO", message = "provider는 GOOGLE 또는 KAKAO만 허용됩니다.")
        String provider,

        @NotBlank(message = "소셜 로그인 토큰은 필수입니다.")
        String providerToken
) {
}