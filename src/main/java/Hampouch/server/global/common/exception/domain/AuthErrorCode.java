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

    INVALID_EMAIL_OR_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_EMAIL_OR_PASSWORD", "이메일 또는 비밀번호가 올바르지 않습니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),

    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_TOKEN", "만료된 토큰입니다."),

    UNSUPPORTED_TOKEN(HttpStatus.UNAUTHORIZED, "UNSUPPORTED_TOKEN", "지원하지 않는 토큰입니다."),

    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "리프레시 토큰을 찾을 수 없습니다."),

    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 리프레시 토큰입니다."),

    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_REFRESH_TOKEN", "만료된 리프레시 토큰입니다."),

    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),

    SOCIAL_ACCOUNT_ONLY(HttpStatus.BAD_REQUEST, "SOCIAL_ACCOUNT_ONLY", "소셜 로그인으로 가입된 계정입니다."),

    LOCAL_ACCOUNT_ONLY(HttpStatus.BAD_REQUEST, "LOCAL_ACCOUNT_ONLY", "일반 로그인으로 가입된 계정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
