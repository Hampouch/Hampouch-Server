package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum UserErrorCode implements BaseErrorCode {

    USER_NOT_FOUND("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    USER_DELETED("USER_DELETED", "탈퇴한 회원입니다.", HttpStatus.FORBIDDEN),
    USER_NICKNAME_ALREADY_EXISTS("USER_NICKNAME_ALREADY_EXISTS", "이미 존재하는 닉네임입니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    UserErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
