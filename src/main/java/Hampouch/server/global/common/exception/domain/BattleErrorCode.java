package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BattleErrorCode implements BaseErrorCode {

    INVALID_CAPACITY_RANGE(HttpStatus.BAD_REQUEST, "INVALID_CAPACITY_RANGE", "참가 정원은 2명 이상 10명 이하로 설정해야 합니다."),

    INVALID_START_DATE(HttpStatus.BAD_REQUEST, "INVALID_START_DATE", "시작일은 오늘 이후 날짜여야 합니다."),

    // capacity(INVALID_CAPACITY_RANGE)·startDate(INVALID_START_DATE)와 대칭을 맞추려고 신설.
    // 이전엔 CommonErrorCode.VALIDATION_ERROR + 커스텀 메시지였는데, 그 경로는 fieldErrors가 없는
    // 다른 shape로 나가서 프론트가 생성 폼의 세 필드를 같은 방식으로 처리할 수 없었다.
    INVALID_DURATION_DAYS(HttpStatus.BAD_REQUEST, "INVALID_DURATION_DAYS", "참가 기간은 3일, 7일, 14일, 31일 중에서 선택해야 합니다."),

    BATTLE_NOT_FOUND(HttpStatus.NOT_FOUND, "BATTLE_NOT_FOUND", "요청한 햄배틀을 찾을 수 없습니다."),
    
    BATTLE_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "BATTLE_CODE_NOT_FOUND", "요청한 초대 코드를 찾을 수 없습니다."),

    BATTLE_FULL(HttpStatus.CONFLICT, "BATTLE_FULL", "정원이 마감된 햄배틀입니다."),

    BATTLE_ALREADY_STARTED(HttpStatus.CONFLICT, "BATTLE_ALREADY_STARTED", "이미 시작된 햄배틀에는 참가할 수 없습니다."),

    ALREADY_JOINED(HttpStatus.CONFLICT, "ALREADY_JOINED", "이미 참가한 햄배틀입니다."),

    BATTLE_CANCELLED(HttpStatus.BAD_REQUEST, "BATTLE_CANCELLED", "참가 인원이 모이지 않아 취소된 햄배틀입니다."),

    FORBIDDEN_NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_PARTICIPANT", "햄배틀 정보는 참가자만 조회할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
