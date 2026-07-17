package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 휴식(#8) 도메인 에러 코드 — 나연 공통 예외 프레임워크(BaseErrorCode)에 등록.
 * 상수 이름은 본챌린지 명세 §0의 코드 등록표에 예약해 둔 이름(REST_ALREADY_ACTIVE·REST_NOT_ACTIVE) 그대로 —
 * 안드가 code 문자열로 분기하는 계약이라 표와 글자 단위로 맞춘다.
 * "진행 중 챌린지가 있어 휴식 불가" 409는 신설 없이 ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS를
 * 재사용한다(메시지 "이미 진행 중인 챌린지가 있습니다."가 그대로 들어맞음 — 명세 §1).
 */
@Getter
@RequiredArgsConstructor
public enum RestErrorCode implements BaseErrorCode {

    REST_ALREADY_ACTIVE(HttpStatus.CONFLICT, "REST_ALREADY_ACTIVE", "이미 휴식 중입니다."),
    REST_NOT_ACTIVE(HttpStatus.NOT_FOUND, "REST_NOT_ACTIVE", "휴식 중이 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
