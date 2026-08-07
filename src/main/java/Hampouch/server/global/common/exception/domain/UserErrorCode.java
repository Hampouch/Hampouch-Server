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
    USER_NICKNAME_ALREADY_SET(HttpStatus.CONFLICT, "USER_NICKNAME_ALREADY_SET", "이미 닉네임이 설정된 계정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}