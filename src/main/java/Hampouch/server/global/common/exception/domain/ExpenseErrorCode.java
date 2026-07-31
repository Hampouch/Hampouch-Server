package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * expense 도메인 전용 에러 코드.
 * FORBIDDEN이 Common/Auth/Challenge에 각자 따로 있는 것과 동일하게, 개념이 겹치는 코드(예: 챌린지 기간 밖 날짜)라도
 * 다른 도메인의 ErrorCode를 재사용하지 않고 이 도메인 전용으로 둔다 — 도메인 경계를 ErrorCode 레벨에서도 유지.
 */
@Getter
@RequiredArgsConstructor
public enum ExpenseErrorCode implements BaseErrorCode {

    EXPENSE_NOT_FOUND(HttpStatus.NOT_FOUND, "EXPENSE_NOT_FOUND", "요청한 지출 내역을 찾을 수 없습니다."),

    EXPENSE_FORBIDDEN(HttpStatus.FORBIDDEN, "EXPENSE_FORBIDDEN", "해당 지출 내역에 접근 권한이 없습니다."),

    EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD(HttpStatus.BAD_REQUEST, "EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD", "진행 중인 메인 챌린지 기간 내에서만 지출 입력이 가능합니다."),

    EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED", "카테고리를 직접 입력한 경우 기존 카테고리와 명칭이 달라야 합니다."),

    EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED", "이유를 직접 입력한 경우 기존 제시된 이유와 달라야 합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
