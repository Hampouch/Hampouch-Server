package Hampouch.server.domain.auth.service;

import Hampouch.server.domain.auth.dto.request.*;
import Hampouch.server.domain.auth.dto.response.*;
import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.RefreshToken;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.repository.RefreshTokenRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final long EMAIL_CODE_EXPIRES_IN_SECONDS = 600L;
    private static final int EMAIL_CODE_LENGTH = 6;

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final Clock clock;
    private final EmailSender emailSender;
    private final List<SocialTokenVerifier> socialTokenVerifiers;

    //이메일 인증번호 발송
    @Transactional
    public EmailSendResponse sendEmailVerification(EmailSendRequest request) {
        VerificationPurpose purpose = VerificationPurpose.valueOf(request.purpose());
        String email = request.email();

        validateEmailForPurpose(email, purpose);

        String code = generateCode();
        LocalDateTime expiredAt = LocalDateTime.now(clock).plusSeconds(EMAIL_CODE_EXPIRES_IN_SECONDS);

        emailVerificationRepository.save(EmailVerification.create(email, code, purpose, expiredAt));

        try {
            emailSender.send(email, code, purpose);
        } catch (Exception e) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_SEND_FAILED);
        }

        return EmailSendResponse.of(EMAIL_CODE_EXPIRES_IN_SECONDS);
    }

    private void validateEmailForPurpose(String email, VerificationPurpose purpose) {
        if (purpose == VerificationPurpose.SIGNUP) {
            userRepository.findByEmail(email).ifPresent(user -> {
                if (user.isLocalUser()) {
                    throw new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
                }
                throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH, loginTypeMismatchMessage(user.getProvider()));
            });
            return;
        }

        //PASSWORD_RESET의 경우
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isSocialUser()) {
            throw new CustomException(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED);
        }
    }

    private String generateCode() {
        Random random = new Random();
        int code = random.nextInt(1_000_000); // 0 ~ 999999
        return String.format("%0" + EMAIL_CODE_LENGTH + "d", code);
    }

    //이메일 인증번호 확인
    @Transactional
    public EmailVerifyResponse verifyEmail(EmailVerifyRequest request) {
        VerificationPurpose purpose = VerificationPurpose.valueOf(request.purpose());

        EmailVerification verification = emailVerificationRepository
                .findEmailAndPurpose(request.email(), purpose)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (verification.isExpired(now)) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_CODE_EXPIRED);
        }

        if (!verification.isCodeMatch(request.code())) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_CODE_MISMATCH);
        }

        verification.verify();

        return EmailVerifyResponse.of(request.email(), purpose.name(), true);
    }

    //닉네임 중복 확인
    public NicknameCheckResponse checkNickname(String nickname) {
        boolean exists = userRepository.existsByNickname(nickname);

        if (exists) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }

        return NicknameCheckResponse.of(nickname, true);
    }

    //일반 회원가입
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email();

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isLocalUser()) {
                throw new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
            }
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH, loginTypeMismatchMessage(user.getProvider()));
        });

        EmailVerification verification = emailVerificationRepository
                .findEmailAndPurpose(email, VerificationPurpose.SIGNUP)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED));

        if (!verification.isVerified()) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.createLocalUser(email, encodedPassword, request.nickname());
        userRepository.save(user);

        return SignupResponse.of(user.getId(), user.getEmail(), user.getNickname(), user.getProvider().name());
    }

    //일반 로그인
    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED));

        if (!user.isLocalUser()) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH, loginTypeMismatchMessage(user.getProvider()));
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED);
        }

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        TokenReissueResponse tokens = issueTokens(user);

        return LoginResponse.of(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresInMs(),
                tokens.refreshTokenExpiresInMs(),
                LoginResponse.UserSummary.of(user.getId(), user.getRole().name(), user.getStatus().name())
        );
    }

    //소셜 로그인 / 회원가입
    @Transactional
    public SocialLoginResponse socialLogin(SocialLoginRequest request) {
        AuthProvider provider = AuthProvider.valueOf(request.provider());

        SocialTokenVerifier verifier = socialTokenVerifiers.stream()
                .filter(v -> v.supports(provider.name()))
                .findFirst()
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_UNSUPPORTED_PROVIDER));

        SocialTokenVerifier.SocialUserInfo socialInfo = verifier.verify(request.providerToken());

        if (socialInfo.email() == null || socialInfo.email().isBlank()) {
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_EMAIL_NOT_PROVIDED);
        }

        boolean isNewUser;
        User user = userRepository.findByEmail(socialInfo.email()).orElse(null);

        if (user == null) {
            user = User.createSocialUser(
                    socialInfo.email(),
                    socialInfo.nickname(),
                    socialInfo.profileImageUrl(),
                    provider,
                    socialInfo.providerId()
            );
            userRepository.save(user);
            isNewUser = true;
        } else {
            if (user.getProvider() != provider) {
                throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH, loginTypeMismatchMessage(user.getProvider()));
            }
            if (user.isDeleted()) {
                throw new CustomException(UserErrorCode.USER_DELETED);
            }
            isNewUser = false;
        }

        TokenReissueResponse tokens = issueTokens(user);

        return SocialLoginResponse.of(
                isNewUser,
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresInMs(),
                tokens.refreshTokenExpiresInMs(),
                LoginResponse.UserSummary.of(user.getId(), user.getRole().name(), user.getStatus().name())
        );
    }

    //토큰 재발급
    @Transactional
    public TokenReissueResponse reissueToken(RefreshRequest request) {
        Long userId = jwtProvider.getUserIdFromRefreshToken(request.refreshToken());

        RefreshToken savedToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        LocalDateTime now = LocalDateTime.now(clock);

        if (savedToken.isRevoked()) {
            throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_REVOKED);
        }
        if (savedToken.isExpired(now)) {
            throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        //재발급 시 기존 refresh token은 폐기(rotation)
        savedToken.revoke();

        return issueTokens(user);
    }

    //로그아웃
    @Transactional
    public void logout(Long userId, RefreshRequest request) {
        RefreshToken savedToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        if (!savedToken.getUserId().equals(userId)) {
            throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        savedToken.revoke();
    }

    //비밀번호 재설정
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = request.email();

        EmailVerification verification = emailVerificationRepository
                .findEmailAndPurpose(email, VerificationPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED));

        if (!verification.isVerified()) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isSocialUser()) {
            throw new CustomException(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED);
        }

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        user.resetPassword(passwordEncoder.encode(request.newPassword()));
    }

    //회원 탈퇴
    @Transactional
    public void deleteMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        user.delete();
        refreshTokenRepository.revokeAllByUserId(userId);
    }


    private TokenReissueResponse issueTokens(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        LocalDateTime expiredAt = LocalDateTime.now(clock)
                .plus(Duration.ofMillis(jwtProvider.getRefreshTokenExpiresInMs()));

        refreshTokenRepository.save(RefreshToken.create(user.getId(), refreshToken, expiredAt));

        return TokenReissueResponse.of(
                accessToken,
                refreshToken,
                jwtProvider.getAccessTokenExpiresInMs(),
                jwtProvider.getRefreshTokenExpiresInMs()
        );
    }

    private String loginTypeMismatchMessage(AuthProvider provider) {
        String providerName = switch (provider) {
            case LOCAL -> "일반";
            case GOOGLE -> "구글";
            case KAKAO -> "카카오";
        };
        return String.format("이미 %s 로그인으로 가입된 이메일입니다. %s 로그인을 이용해주세요.", providerName, providerName);
    }
}