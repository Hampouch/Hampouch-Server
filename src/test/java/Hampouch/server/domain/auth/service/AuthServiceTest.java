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
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    EmailVerificationRepository emailVerificationRepository;
    @Mock
    RefreshTokenRepository refreshTokenRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    JwtProvider jwtProvider;
    @Mock
    EmailSender emailSender;
    @Mock
    SocialTokenVerifier socialTokenVerifier;

    AuthService authService;

    // 테스트 결정성을 위해 Clock을 고정 시각으로 주입 (프로젝트 공용 Clock 컨벤션과 동일한 방식)
    final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 22, 12, 0);
    final Clock clock = Clock.fixed(FIXED_NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                emailVerificationRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtProvider,
                clock,
                emailSender,
                List.of(socialTokenVerifier)
        );
    }

    private User localUser(String email, String encodedPassword, boolean deleted) {
        User user = User.createLocalUser(email, encodedPassword, "닉네임");
        setField(user, "id", 1L);
        if (deleted) {
            user.delete();
        }
        return user;
    }

    private User socialUser(AuthProvider provider, String email) {
        User user = User.createSocialUser(email, provider, "provider-id");
        setField(user, "id", 2L);
        return user;
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========== 1. sendEmailVerification ==========

    @Test
    void 회원가입_인증발송_이미_로컬가입된_이메일이면_예외() {
        User existing = localUser("test@example.com", "encoded", false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        EmailSendRequest request = new EmailSendRequest("test@example.com", "SIGNUP");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
    }

    @Test
    void 회원가입_인증발송_소셜가입된_이메일이면_로그인타입불일치_예외() {
        User existing = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        EmailSendRequest request = new EmailSendRequest("test@example.com", "SIGNUP");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
    }

    @Test
    void 회원가입_인증발송_신규이메일이면_저장하고_발송한다() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty()); // 이전 발송 기록 없음 -> 쿨다운 통과

        EmailSendRequest request = new EmailSendRequest("new@example.com", "SIGNUP");
        EmailSendResponse response = authService.sendEmailVerification(request);

        assertThat(response.expiresInSeconds()).isEqualTo(600L);
        verify(emailVerificationRepository).save(any(EmailVerification.class));
        verify(emailSender).send(eq("new@example.com"), anyString(), eq(VerificationPurpose.SIGNUP));
    }

    @Test
    void 회원가입_인증발송_직전_발송으로부터_30초_이내면_예외() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        EmailVerification lastSent = EmailVerification.create(
                "new@example.com", "111111", VerificationPurpose.SIGNUP, FIXED_NOW.plusMinutes(10));
        setField(lastSent, "createdAt", FIXED_NOW.minusSeconds(15)); // 15초 전 발송 -> 30초 쿨다운 안 지남
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(lastSent));

        EmailSendRequest request = new EmailSendRequest("new@example.com", "SIGNUP");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_SEND_TOO_FREQUENT);

        verify(emailVerificationRepository, never()).save(any());
        verify(emailSender, never()).send(anyString(), anyString(), any());
    }

    @Test
    void 회원가입_인증발송_직전_발송으로부터_30초_지났으면_정상발송() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        EmailVerification lastSent = EmailVerification.create(
                "new@example.com", "111111", VerificationPurpose.SIGNUP, FIXED_NOW.plusMinutes(10));
        setField(lastSent, "createdAt", FIXED_NOW.minusSeconds(31)); // 31초 전 발송 -> 쿨다운 지남
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(lastSent));

        EmailSendRequest request = new EmailSendRequest("new@example.com", "SIGNUP");
        EmailSendResponse response = authService.sendEmailVerification(request);

        assertThat(response.expiresInSeconds()).isEqualTo(600L);
        verify(emailVerificationRepository).save(any(EmailVerification.class));
    }

    @Test
    void 비밀번호재설정_인증발송_가입안된_이메일이면_예외() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        EmailSendRequest request = new EmailSendRequest("unknown@example.com", "PASSWORD_RESET");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 비밀번호재설정_인증발송_소셜계정이면_예외() {
        User existing = socialUser(AuthProvider.KAKAO, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        EmailSendRequest request = new EmailSendRequest("test@example.com", "PASSWORD_RESET");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED);
    }

    @Test
    void 인증발송_메일전송_실패시_발송실패_예외() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty()); // 이전 발송 기록 없음 -> 쿨다운 통과
        doThrow(new RuntimeException("smtp down")).when(emailSender).send(anyString(), anyString(), any());

        EmailSendRequest request = new EmailSendRequest("new@example.com", "SIGNUP");

        assertThatThrownBy(() -> authService.sendEmailVerification(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_SEND_FAILED);
    }

    // ========== 2. verifyEmail ==========

    @Test
    void 이메일인증_확인_요청이없으면_예외() {
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        EmailVerifyRequest request = new EmailVerifyRequest("test@example.com", "123456", "SIGNUP");

        assertThatThrownBy(() -> authService.verifyEmail(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND);
    }

    @Test
    void 이메일인증_확인_코드만료시_예외() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.minusMinutes(1));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        EmailVerifyRequest request = new EmailVerifyRequest("test@example.com", "123456", "SIGNUP");

        assertThatThrownBy(() -> authService.verifyEmail(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_CODE_EXPIRED);
    }

    @Test
    void 이메일인증_확인_시도횟수초과시_예외() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.plusMinutes(10));
        for (int i = 0; i < 5; i++) {
            verification.increaseAttempt();
        }
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        EmailVerifyRequest request = new EmailVerifyRequest("test@example.com", "999999", "SIGNUP");

        assertThatThrownBy(() -> authService.verifyEmail(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_CODE_ATTEMPT_EXCEEDED);
    }

    @Test
    void 이메일인증_확인_코드불일치시_예외이고_시도횟수가_증가한다() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.plusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        EmailVerifyRequest request = new EmailVerifyRequest("test@example.com", "000000", "SIGNUP");

        assertThatThrownBy(() -> authService.verifyEmail(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_CODE_MISMATCH);

        assertThat(verification.isAttemptExceeded()).isFalse(); // 1회 실패는 아직 초과 아님
    }

    @Test
    void 이메일인증_확인_성공하면_인증완료로_변경된다() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.plusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        EmailVerifyRequest request = new EmailVerifyRequest("test@example.com", "123456", "SIGNUP");
        EmailVerifyResponse response = authService.verifyEmail(request);

        assertThat(response.verified()).isTrue();
        assertThat(verification.isVerified()).isTrue();
    }

    // ========== 3. checkNickname ==========

    @Test
    void 닉네임_중복이면_예외() {
        when(userRepository.existsByNickname("햄포치")).thenReturn(true);

        assertThatThrownBy(() -> authService.checkNickname("햄포치"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
    }

    @Test
    void 닉네임_사용가능하면_available_true() {
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);

        NicknameCheckResponse response = authService.checkNickname("새닉네임");

        assertThat(response.available()).isTrue();
    }

    // ========== 4. signup ==========

    @Test
    void 회원가입_이미가입된_이메일이면_예외() {
        User existing = localUser("test@example.com", "encoded", false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        SignupRequest request = new SignupRequest("test@example.com", "password1!", "닉네임");

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);

        verifyNoInteractions(emailVerificationRepository);
    }

    @Test
    void 회원가입_이메일_인증기록이_없으면_예외() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        SignupRequest request = new SignupRequest("new@example.com", "password1!", "닉네임");

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void 회원가입_인증은됐지만_1시간_지났으면_예외() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        EmailVerification verification = EmailVerification.create(
                "new@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.minusHours(2));
        verification.verify(FIXED_NOW.minusHours(2)); // 2시간 전에 인증 완료 -> 1시간 유효시간 지남
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        SignupRequest request = new SignupRequest("new@example.com", "password1!", "닉네임");

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void 회원가입_정상흐름이면_유저가_생성된다() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        EmailVerification verification = EmailVerification.create(
                "new@example.com", "123456", VerificationPurpose.SIGNUP, FIXED_NOW.minusMinutes(30));
        verification.verify(FIXED_NOW.minusMinutes(10)); // 10분 전 인증 완료 -> 유효
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));
        when(passwordEncoder.encode("password1!")).thenReturn("encoded-password");

        SignupRequest request = new SignupRequest("new@example.com", "password1!", "닉네임");
        SignupResponse response = authService.signup(request);

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.nickname()).isEqualTo("닉네임");
        assertThat(response.provider()).isEqualTo("LOCAL");
        verify(userRepository).save(any(User.class));
    }

    // ========== 5. login ==========

    @Test
    void 로그인_존재하지않는_이메일이면_로그인실패_예외() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("unknown@example.com", "password1!");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_LOGIN_FAILED);
    }

    @Test
    void 로그인_소셜계정이면_로그인타입불일치_예외() {
        User user = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("test@example.com", "password1!");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
    }

    @Test
    void 로그인_비밀번호_불일치시_예외() {
        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        LoginRequest request = new LoginRequest("test@example.com", "wrong");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_LOGIN_FAILED);
    }

    @Test
    void 로그인_탈퇴한_회원이면_예외() {
        User user = localUser("test@example.com", "encoded", true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1!", "encoded")).thenReturn(true);

        LoginRequest request = new LoginRequest("test@example.com", "password1!");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 로그인_정상흐름이면_토큰이_발급된다() {
        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1!", "encoded")).thenReturn(true);
        when(jwtProvider.createAccessToken(1L, UserRole.USER)).thenReturn("access-token");
        when(jwtProvider.createRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenExpiresInMs()).thenReturn(1_209_600_000L);
        when(jwtProvider.getAccessTokenExpiresInMs()).thenReturn(3_600_000L);

        LoginRequest request = new LoginRequest("test@example.com", "password1!");
        LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().userId()).isEqualTo(1L);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    // ========== 6. socialLogin ==========

    @Test
    void 소셜로그인_지원하지않는_provider면_예외() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(false);

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");

        assertThatThrownBy(() -> authService.socialLogin(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_UNSUPPORTED_PROVIDER);
    }

    @Test
    void 소셜로그인_이메일_미제공시_예외() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify("provider-token"))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo(null, "provider-id"));

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");

        assertThatThrownBy(() -> authService.socialLogin(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_SOCIAL_EMAIL_NOT_PROVIDED);
    }

    @Test
    void 소셜로그인_신규유저면_생성되고_isNewUser_true() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify("provider-token"))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo("new@example.com", "provider-id"));
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(jwtProvider.createAccessToken(any(), any())).thenReturn("access-token");
        when(jwtProvider.createRefreshToken(any())).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenExpiresInMs()).thenReturn(1_209_600_000L);
        when(jwtProvider.getAccessTokenExpiresInMs()).thenReturn(3_600_000L);

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");
        SocialLoginResponse response = authService.socialLogin(request);

        assertThat(response.isNewUser()).isTrue();
        verify(userRepository).save(any(User.class));
    }

    @Test
    void 소셜로그인_다른_provider로_가입된_이메일이면_예외() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify("provider-token"))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo("test@example.com", "provider-id"));

        User existing = socialUser(AuthProvider.KAKAO, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");

        assertThatThrownBy(() -> authService.socialLogin(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_LOGIN_TYPE_MISMATCH);
    }

    @Test
    void 소셜로그인_탈퇴한_기존유저면_예외() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify("provider-token"))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo("test@example.com", "provider-id"));

        User existing = socialUser(AuthProvider.GOOGLE, "test@example.com");
        existing.delete();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");

        assertThatThrownBy(() -> authService.socialLogin(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 소셜로그인_기존유저면_isNewUser_false() {
        when(socialTokenVerifier.supports("GOOGLE")).thenReturn(true);
        when(socialTokenVerifier.verify("provider-token"))
                .thenReturn(new SocialTokenVerifier.SocialUserInfo("test@example.com", "provider-id"));

        User existing = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existing));
        when(jwtProvider.createAccessToken(any(), any())).thenReturn("access-token");
        when(jwtProvider.createRefreshToken(any())).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenExpiresInMs()).thenReturn(1_209_600_000L);
        when(jwtProvider.getAccessTokenExpiresInMs()).thenReturn(3_600_000L);

        SocialLoginRequest request = new SocialLoginRequest("GOOGLE", "provider-token");
        SocialLoginResponse response = authService.socialLogin(request);

        assertThat(response.isNewUser()).isFalse();
        verify(userRepository, never()).save(any());
    }

    // ========== 7. reissueToken ==========

    @Test
    void 토큰재발급_저장된_토큰이_없으면_예외() {
        when(jwtProvider.getUserIdFromRefreshToken("refresh-token")).thenReturn(1L);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        RefreshRequest request = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> authService.reissueToken(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    void 토큰재발급_이미_폐기된_토큰이면_예외() {
        when(jwtProvider.getUserIdFromRefreshToken("refresh-token")).thenReturn(1L);
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.plusDays(1));
        saved.revoke();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        RefreshRequest request = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> authService.reissueToken(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_REVOKED);
    }

    @Test
    void 토큰재발급_만료된_토큰이면_예외() {
        when(jwtProvider.getUserIdFromRefreshToken("refresh-token")).thenReturn(1L);
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.minusDays(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        RefreshRequest request = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> authService.reissueToken(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
    }

    @Test
    void 토큰재발급_탈퇴한_유저면_예외() {
        when(jwtProvider.getUserIdFromRefreshToken("refresh-token")).thenReturn(1L);
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.plusDays(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        User user = localUser("test@example.com", "encoded", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        RefreshRequest request = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> authService.reissueToken(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 토큰재발급_정상흐름이면_기존토큰은_폐기되고_새토큰이_발급된다() {
        when(jwtProvider.getUserIdFromRefreshToken("refresh-token")).thenReturn(1L);
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.plusDays(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtProvider.createAccessToken(any(), any())).thenReturn("new-access-token");
        when(jwtProvider.createRefreshToken(any())).thenReturn("new-refresh-token");
        when(jwtProvider.getRefreshTokenExpiresInMs()).thenReturn(1_209_600_000L);
        when(jwtProvider.getAccessTokenExpiresInMs()).thenReturn(3_600_000L);

        RefreshRequest request = new RefreshRequest("refresh-token");
        TokenReissueResponse response = authService.reissueToken(request);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(saved.isRevoked()).isTrue(); // 기존 토큰 rotation 폐기 확인
    }

    // ========== 8. logout ==========

    @Test
    void 로그아웃_저장된_토큰이_없으면_예외() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        RefreshRequest request = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> authService.logout(1L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    void 로그아웃_토큰소유자가_다르면_예외() {
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.plusDays(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        RefreshRequest request = new RefreshRequest("refresh-token");

        // 토큰 소유자는 userId=1L인데 로그아웃 요청자는 999L -> 남의 토큰으로 로그아웃 시도 차단
        assertThatThrownBy(() -> authService.logout(999L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    void 로그아웃_정상흐름이면_토큰이_폐기된다() {
        RefreshToken saved = RefreshToken.create(1L, "hash", FIXED_NOW.plusDays(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        RefreshRequest request = new RefreshRequest("refresh-token");
        authService.logout(1L, request);

        assertThat(saved.isRevoked()).isTrue();
    }

    // ========== 9. resetPassword ==========

    @Test
    void 비밀번호재설정_인증기록이_없으면_예외() {
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "newPassword1!");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void 비밀번호재설정_가입안된_이메일이면_예외() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.PASSWORD_RESET, FIXED_NOW.minusMinutes(30));
        verification.verify(FIXED_NOW.minusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());

        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "newPassword1!");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 비밀번호재설정_소셜계정이면_예외() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.PASSWORD_RESET, FIXED_NOW.minusMinutes(30));
        verification.verify(FIXED_NOW.minusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        User user = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "newPassword1!");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED);
    }

    @Test
    void 비밀번호재설정_탈퇴한_회원이면_예외() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.PASSWORD_RESET, FIXED_NOW.minusMinutes(30));
        verification.verify(FIXED_NOW.minusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        User user = localUser("test@example.com", "old-encoded", true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "newPassword1!");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 비밀번호재설정_정상흐름이면_비밀번호가_변경된다() {
        EmailVerification verification = EmailVerification.create(
                "test@example.com", "123456", VerificationPurpose.PASSWORD_RESET, FIXED_NOW.minusMinutes(30));
        verification.verify(FIXED_NOW.minusMinutes(10));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(verification));

        User user = localUser("test@example.com", "old-encoded", false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword1!")).thenReturn("new-encoded");

        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "newPassword1!");
        authService.resetPassword(request);

        assertThat(user.getPassword()).isEqualTo("new-encoded");
    }

    // ========== 10. deleteMe ==========

    @Test
    void 회원탈퇴_존재하지않는_유저면_예외() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.deleteMe(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 회원탈퇴_이미_탈퇴한_유저면_예외() {
        User user = localUser("test@example.com", "encoded", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.deleteMe(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 회원탈퇴_정상흐름이면_상태변경과_토큰전체폐기가_같이_일어난다() {
        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        authService.deleteMe(1L);

        assertThat(user.isDeleted()).isTrue();
        verify(refreshTokenRepository).revokeAllByUserId(1L);
    }

    // ========== 11. setInitialNickname ==========

    @Test
    void 닉네임최초설정_존재하지않는_유저면_예외() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        NicknameSetRequest request = new NicknameSetRequest("새닉네임");

        assertThatThrownBy(() -> authService.setInitialNickname(1L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 닉네임최초설정_탈퇴한_유저면_예외() {
        User user = localUser("test@example.com", "encoded", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        NicknameSetRequest request = new NicknameSetRequest("새닉네임");

        assertThatThrownBy(() -> authService.setInitialNickname(1L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 닉네임최초설정_이미_닉네임이_설정된_유저면_예외() {
        // localUser 헬퍼는 이미 "닉네임"으로 설정된 유저를 만들어줌 -> hasNickname() true
        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        NicknameSetRequest request = new NicknameSetRequest("새닉네임");

        assertThatThrownBy(() -> authService.setInitialNickname(1L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NICKNAME_ALREADY_SET);

        verify(userRepository, never()).existsByNickname(anyString());
    }

    @Test
    void 닉네임최초설정_이미_존재하는_닉네임이면_예외() {
        // socialUser 헬퍼는 닉네임 없이(null) 생성된 유저 -> hasNickname() false
        User user = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("중복닉네임")).thenReturn(true);

        NicknameSetRequest request = new NicknameSetRequest("중복닉네임");

        assertThatThrownBy(() -> authService.setInitialNickname(2L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
    }

    @Test
    void 닉네임최초설정_정상흐름이면_닉네임이_설정된다() {
        User user = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);

        NicknameSetRequest request = new NicknameSetRequest("새닉네임");
        NicknameSetResponse response = authService.setInitialNickname(2L, request);

        assertThat(response.userId()).isEqualTo(2L);
        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.hasNickname()).isTrue();
    }

    // ========== 12. getMe ==========

    @Test
    void 내정보조회_존재하지않는_유저면_예외() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getMe(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 내정보조회_탈퇴한_유저면_예외() {
        User user = localUser("test@example.com", "encoded", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.getMe(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(UserErrorCode.USER_DELETED);
    }

    @Test
    void 내정보조회_닉네임_없으면_needsNickname_true() {
        User user = socialUser(AuthProvider.GOOGLE, "test@example.com");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        AuthMeResponse response = authService.getMe(2L);

        assertThat(response.userId()).isEqualTo(2L);
        assertThat(response.nickname()).isNull();
        assertThat(response.needsNickname()).isTrue();
    }

    @Test
    void 내정보조회_닉네임_있으면_needsNickname_false() {
        User user = localUser("test@example.com", "encoded", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthMeResponse response = authService.getMe(1L);

        assertThat(response.nickname()).isEqualTo("닉네임");
        assertThat(response.needsNickname()).isFalse();
        assertThat(response.role()).isEqualTo("USER");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }
}