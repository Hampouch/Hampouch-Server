package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements BaseErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    USER_DELETED(HttpStatus.FORBIDDEN, "USER_DELETED", "탈퇴한 회원입니다."),
    USER_NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_NICKNAME_ALREADY_EXISTS", "이미 존재하는 닉네임입니다."),
    USER_NICKNAME_ALREADY_SET(HttpStatus.CONFLICT, "USER_NICKNAME_ALREADY_SET", "이미 닉네임이 설정된 계정입니다."),
    USER_PROFILE_IMAGE_KEY_FORBIDDEN(HttpStatus.FORBIDDEN, "USER_PROFILE_IMAGE_KEY_FORBIDDEN", "본인이 발급받은 이미지만 사용할 수 있습니다."),
    USER_PROFILE_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "USER_PROFILE_IMAGE_NOT_UPLOADED", "업로드가 확인되지 않은 이미지입니다."),
    USER_PROFILE_IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "USER_PROFILE_IMAGE_SIZE_EXCEEDED", "이미지 크기는 최대 10MB까지 등록할 수 있습니다."),
    USER_PROFILE_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "USER_PROFILE_IMAGE_UPLOAD_FAILED", "이미지 업로드 처리 중 오류가 발생했습니다."),
    USER_CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "USER_CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."),
    USER_SOCIAL_PASSWORD_CHANGE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "USER_SOCIAL_PASSWORD_CHANGE_NOT_ALLOWED", "소셜 로그인 계정은 비밀번호를 변경할 수 없습니다."),
    NOTIFICATION_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_SCHEDULE_NOT_FOUND", "알림 설정을 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}