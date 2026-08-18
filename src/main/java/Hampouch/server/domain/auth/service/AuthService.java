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
import Hampouch.server.domain.user.entity.NotificationSchedule;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.NotificationScheduleRepository;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final long EMAIL_CODE_EXPIRES_IN_SECONDS = 600L;
    private static final long EMAIL_RESEND_COOLDOWN_SECONDS = 30L; //재발송 최소 간격
    private static final int EMAIL_CODE_LENGTH = 6;

    private final UserRepository userRepository;
    private final NotificationScheduleRepository notificationScheduleRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final UserOperationLock userOperationLock;
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
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiredAt = now.plusSeconds(
                EMAIL_CODE_EXPIRES_IN_SECONDS
        );

        EmailVerification verification =
                emailVerificationRepository
                        .findByEmailAndPurposeForUpdate(email, purpose)
                        .orElse(null);

        if (verification == null) {
            try {
                emailVerificationRepository.saveAndFlush(
                        EmailVerification.create(
                                email,
                                code,
                                purpose,
                                expiredAt
                        )
                );
            } catch (DataIntegrityViolationException e) {
                if (hasConstraint(
                        e,
                        "uk_email_verification_email_purpose"
                )) {
                    throw new CustomException(
                            AuthErrorCode.AUTH_EMAIL_SEND_TOO_FREQUENT
                    );
                }

                throw e;
            } catch (CannotAcquireLockException e) {
                //동일 이메일·목적의 최초 행을 동시에 생성하면서MySQL gap/insert lock 경쟁이 발생한 경우
                throw new CustomException(
                        AuthErrorCode.AUTH_EMAIL_SEND_TOO_FREQUENT
                );
            }
        } else {
            validateResendCooldown(verification, now);
            verification.reissue(code, expiredAt);
        }

        try {
            emailSender.send(email, code, purpose);
        } catch (Exception e) {
            throw new CustomException(
                    AuthErrorCode.AUTH_EMAIL_SEND_FAILED
            );
        }

        return EmailSendResponse.of(EMAIL_CODE_EXPIRES_IN_SECONDS);
    }

    private void validateResendCooldown(EmailVerification verification, LocalDateTime now
    ) {
        LocalDateTime lastSentAt = verification.getExpiredAt().minusSeconds(EMAIL_CODE_EXPIRES_IN_SECONDS);

        LocalDateTime cooldownEnd = lastSentAt.plusSeconds(EMAIL_RESEND_COOLDOWN_SECONDS);

        if (now.isBefore(cooldownEnd)) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_SEND_TOO_FREQUENT);
        }

    }

    private void validateEmailForPurpose(String email, VerificationPurpose purpose) {
        if (purpose == VerificationPurpose.SIGNUP) {
            userRepository.findByEmail(email).ifPresent(user -> {
                if (user.isLocalUser()) {
                    throw new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
                }
                throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
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
        SecureRandom random = new SecureRandom();
        int code = random.nextInt(1_000_000);
        return String.format("%0" + EMAIL_CODE_LENGTH + "d", code);
    }

    //이메일 인증번호 확인
    @Transactional(noRollbackFor = CustomException.class)
    public EmailVerifyResponse verifyEmail(EmailVerifyRequest request) {
        VerificationPurpose purpose = VerificationPurpose.valueOf(request.purpose());

        EmailVerification verification = emailVerificationRepository
                .findByEmailAndPurposeForUpdate(request.email(), purpose)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (verification.isExpired(now)) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_CODE_EXPIRED);
        }

        if (verification.isAttemptExceeded()) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_CODE_ATTEMPT_EXCEEDED);
        }

        if (!verification.isCodeMatch(request.code())) {
            verification.increaseAttempt();
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_CODE_MISMATCH);
        }

        verification.verify(now);

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
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
        });

        if (userRepository.existsByNickname(request.nickname())) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }

        EmailVerification verification = emailVerificationRepository
                .findByEmailAndPurpose(email, VerificationPurpose.SIGNUP)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED));

        if (!verification.isVerified()) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }
        if (verification.isVerificationExpired(LocalDateTime.now(clock))) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.createLocalUser(email, encodedPassword, request.nickname());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String rootCauseMessage = e.getMostSpecificCause().getMessage();
            if (rootCauseMessage != null && rootCauseMessage.contains("uk_user_nickname")) {
                throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
            }
            if (rootCauseMessage != null && rootCauseMessage.contains("uk_user_email")) {
                throw new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
            }
            throw e;
        }

        notificationScheduleRepository.save(NotificationSchedule.createDefault(user));

        return SignupResponse.of(user.getId(), user.getEmail(), user.getNickname(), user.getProvider().name());
    }

    //일반 로그인
    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 조회 자체를 잠금 조회로 만듬.
        // --> 트랜잭션의 첫 조회가 일반(non-locking) SELECT면 그 시점에 MySQL REPEATABLE READ 스냅샷이 고정되어, 그 뒤 아무리 다시 읽어도
        // 여전히 그 옛 스냅샷을 보게 되는 문제 - 그 사이 deleteMe가 커밋한 상태 변화가 반영되지 않아 탈퇴한 유저의 로그인이 통과해버렸었음.
        UserRepository.LoginCredentialView credential =
                userRepository.findLoginCredentialByEmail(request.email())
                        .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED));

        if (credential.getProvider() != AuthProvider.LOCAL) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
        }

        String passwordBeforeLock = credential.getPassword();

        if (!passwordEncoder.matches(request.password(), passwordBeforeLock)) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED);
        }

        User user = userRepository.findByEmailForUpdate(request.email())
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED));

        if (!user.isLocalUser()) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
        }

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        //BCrypt 검증과 락 획득 사이에 비밀번호가 변경됐다면 이전 비밀번호로 토큰을 발급하지 않는다
        if (!passwordHashEquals(passwordBeforeLock, user.getPassword())) {
            throw new CustomException(AuthErrorCode.AUTH_LOGIN_FAILED);
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

        //외부 소셜 토큰 검증은 DB 락 전에 수행
        SocialTokenVerifier.SocialUserInfo socialInfo = verifier.verify(request.providerToken());

        if (socialInfo.email() == null || socialInfo.email().isBlank()) {
            throw new CustomException(AuthErrorCode.AUTH_SOCIAL_EMAIL_NOT_PROVIDED);
        }

        String email = socialInfo.email();

        User user = userRepository.findByEmailForUpdate(email).orElse(null);

        boolean isNewUser;

        if (user == null) {
            user = User.createSocialUser(email, provider, socialInfo.providerId());

            try {
                userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException | CannotAcquireLockException e) {
                //동일 소셜 계정의 최초 로그인 요청이 겹쳐 다른 트랜잭션이 같은 email 또는 provider/providerId 사용자를 먼저 생성한 경우
                if (e instanceof CannotAcquireLockException || hasConstraint(e, "uk_user_email")
                        || hasConstraint(e, "uk_users_provider_provider_id")) {
                    throw new CustomException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
                }
                throw e;
            }

            notificationScheduleRepository.save(NotificationSchedule.createDefault(user));

            isNewUser = true;

        } else {
            if (user.getProvider() != provider) {
                throw new CustomException(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
            }
            if (user.isDeleted()) {
                throw new CustomException(UserErrorCode.USER_DELETED);
            }
            isNewUser = false;
        }

        TokenReissueResponse tokens = issueTokens(user);

        return SocialLoginResponse.of(
                isNewUser,
                !user.hasNickname(),
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresInMs(),
                tokens.refreshTokenExpiresInMs(),
                LoginResponse.UserSummary.of(user.getId(), user.getRole().name(), user.getStatus().name())
        );
    }

    //닉네임 최초 설정(소셜 로그인)
    @Transactional
    public NicknameSetResponse setInitialNickname(Long userId, NicknameSetRequest request) {
        userOperationLock.lock(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        if (user.hasNickname()) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_SET);
        }

        if (userRepository.existsByNickname(request.nickname())) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }

        user.setInitialNickname(request.nickname());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (hasConstraint(e, "uk_user_nickname")) {
                throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
            }
            throw e;
        }

        return NicknameSetResponse.of(user.getId(), user.getNickname());
    }

    //토큰 재발급
    @Transactional
    public TokenReissueResponse reissueToken(RefreshRequest request) {
        Long userId = jwtProvider.getUserIdFromRefreshToken(request.refreshToken());
        String tokenHash = hashToken(request.refreshToken());

        // 사용자 행을 먼저 잠근 뒤 refresh token 행을 잠근다 (deleteMe도 사용자 행만 잠그므로 순서가 항상 user → token으로 일관되어 교착이 생기지 않는다)
        userOperationLock.lock(userId);

        RefreshToken savedToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
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

        savedToken.revoke();

        return issueTokens(user);
    }

    //로그아웃
    @Transactional
    public void logout(Long userId, RefreshRequest request) {
        RefreshToken savedToken = refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken()))
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
        LocalDateTime now = LocalDateTime.now(clock);

        EmailVerification verification = emailVerificationRepository
                .findByEmailAndPurposeForUpdate(email, VerificationPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED));

        if (!verification.isVerified()) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }
        if (verification.isVerificationExpired(now)) {
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        // 비밀번호 인코딩(bcrypt, 무거운 연산)은 조회와 무관한 순수 계산이라 먼저 계산해둔다.
        String encodedPassword = passwordEncoder.encode(request.newPassword());

        // login()과 같은 이유로 조회 자체를 잠금 조회로 만든다.
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isSocialUser()) {
            throw new CustomException(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED);
        }

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        user.resetPassword(encodedPassword);
        verification.consume(now);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    // 회원 탈퇴
    @Transactional
    public void deleteMe(Long userId) {
        // 이 메서드는 userId로 곧바로 락을 잡으므로 스냅샷 고정 문제가 없다.
        userOperationLock.lock(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        user.delete();
        userRepository.saveAndFlush(user);

        refreshTokenRepository.revokeAllByUserId(userId);
    }

    //지금 로그인된 사용자의 인증/계정 상태 조회
    public AuthMeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        return AuthMeResponse.of(
                user.getId(),
                user.getNickname(),
                !user.hasNickname(),
                user.getRole().name(),
                user.getStatus().name()
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없습니다.", e);
        }
    }

    private TokenReissueResponse issueTokens(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        LocalDateTime expiredAt = LocalDateTime.now(clock)
                .plus(Duration.ofMillis(jwtProvider.getRefreshTokenExpiresInMs()));

        refreshTokenRepository.save(RefreshToken.create(user.getId(), hashToken(refreshToken), expiredAt));

        return TokenReissueResponse.of(
                accessToken,
                refreshToken,
                jwtProvider.getAccessTokenExpiresInMs(),
                jwtProvider.getRefreshTokenExpiresInMs()
        );
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;

        while (current != null) {
            String message = current.getMessage();

            if (message != null && message.contains(constraintName)) {return true;}

            current = current.getCause();
        }

        return false;
    }

    private boolean passwordHashEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
