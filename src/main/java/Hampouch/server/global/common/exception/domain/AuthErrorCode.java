package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 실제 코드에서 사용 예:
 * throw new CustomException(AuthErrorCode.INVALID_EMAIL_OR_PASSWORD);
 */

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    // 이메일 인증
    AUTH_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."),
    AUTH_LOGIN_TYPE_MISMATCH(HttpStatus.CONFLICT, "AUTH_LOGIN_TYPE_MISMATCH", "이미 다른 방식으로 가입된 이메일입니다."),
    AUTH_PASSWORD_RESET_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_RESET_NOT_ALLOWED", "소셜 로그인 계정은 비밀번호를 재설정할 수 없습니다."),
    AUTH_EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_EMAIL_SEND_FAILED", "이메일 발송에 실패했습니다."),
    AUTH_EMAIL_VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_EMAIL_VERIFICATION_NOT_FOUND", "이메일 인증 요청을 찾을 수 없습니다."),
    AUTH_EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_CODE_MISMATCH", "인증번호를 다시 확인해주세요."),
    AUTH_EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_CODE_EXPIRED", "인증번호가 만료되었습니다. 다시 요청해주세요."),
    AUTH_EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_NOT_VERIFIED", "이메일 인증이 완료되지 않았습니다."),

    // 로그인
    AUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 소셜 로그인
    AUTH_UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "AUTH_UNSUPPORTED_PROVIDER", "지원하지 않는 소셜 로그인입니다."),
    AUTH_SOCIAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_SOCIAL_TOKEN_INVALID", "소셜 로그인 인증에 실패했습니다."),
    AUTH_SOCIAL_EMAIL_NOT_PROVIDED(HttpStatus.BAD_REQUEST, "AUTH_SOCIAL_EMAIL_NOT_PROVIDED", "소셜 계정에서 이메일 정보를 가져올 수 없습니다."),

    // 토큰
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "유효하지 않은 refresh token입니다."),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_EXPIRED", "만료된 refresh token입니다. 다시 로그인해주세요."),
    AUTH_REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_REVOKED", "폐기된 refresh token입니다. 다시 로그인해주세요."),

    // 인증(authentication) 공통
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "인증이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
