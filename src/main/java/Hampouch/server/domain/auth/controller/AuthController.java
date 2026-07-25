package Hampouch.server.domain.auth.controller;

import Hampouch.server.domain.auth.dto.request.*;
import Hampouch.server.domain.auth.dto.response.*;
import Hampouch.server.domain.auth.service.AuthService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    //이메일 인증번호 발송
    @PostMapping("/email/send")
    public ResponseEntity<ApiResponse<EmailSendResponse>> sendEmailVerification(
            @RequestBody @Valid EmailSendRequest request
    ) {
        EmailSendResponse response = authService.sendEmailVerification(request);
        return ResponseEntity.ok(ApiResponse.success("인증번호가 발송되었습니다.", response));
    }

    //이메일 인증번호 확인
    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<EmailVerifyResponse>> verifyEmail(
            @RequestBody @Valid EmailVerifyRequest request
    ) {
        EmailVerifyResponse response = authService.verifyEmail(request);
        return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", response));
    }

    //닉네임 중복 확인
    @GetMapping("/nickname/check")
    public ResponseEntity<ApiResponse<NicknameCheckResponse>> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임을 입력해주세요.")
            @Size(min = 2, max = 30, message = "닉네임은 2자 이상 30자 이하로 입력해주세요.")
            String nickname
    ) {
        NicknameCheckResponse response = authService.checkNickname(nickname);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //일반 회원가입
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @RequestBody @Valid SignupRequest request
    ) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.ok(ApiResponse.success("회원가입이 완료되었습니다.", response));
    }

    //일반 로그인
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody @Valid LoginRequest request
    ) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("로그인에 성공했습니다.", response));
    }

    //소셜 로그인 / 회원가입
    @PostMapping("/social")
    public ResponseEntity<ApiResponse<SocialLoginResponse>> socialLogin(
            @RequestBody @Valid SocialLoginRequest request
    ) {
        SocialLoginResponse response = authService.socialLogin(request);
        String message = response.isNewUser()
                ? "소셜 회원가입 및 로그인에 성공했습니다."
                : "소셜 로그인에 성공했습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    //토큰 재발급
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenReissueResponse>> reissueToken(
            @RequestBody @Valid RefreshRequest request
    ) {
        TokenReissueResponse response = authService.reissueToken(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //로그아웃
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @LoginUserId Long userId,
            @RequestBody @Valid RefreshRequest request
    ) {
        authService.logout(userId, request);
        return ResponseEntity.ok(ApiResponse.success("로그아웃되었습니다.", null));
    }

    //비밀번호 재설정
    @PatchMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @RequestBody @Valid PasswordResetRequest request
    ) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 재설정되었습니다.", null));
    }

    //회원 탈퇴
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMe(
            @LoginUserId Long userId
    ) {
        authService.deleteMe(userId);
        return ResponseEntity.ok(ApiResponse.success("회원 탈퇴가 완료되었습니다.", null));
    }
}