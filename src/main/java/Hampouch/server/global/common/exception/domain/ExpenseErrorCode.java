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

    /** 최종 종료(#50)로 잠긴 기간. 기간 밖 날짜(400)와 달리 요청은 옳고 상태가 막는 것이라 409다. */
    EXPENSE_CHALLENGE_CLOSED(HttpStatus.CONFLICT, "EXPENSE_CHALLENGE_CLOSED", "최종 종료된 챌린지 기간의 기록은 변경할 수 없습니다."),

    EXPENSE_ANALYSIS_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_INVALID_PERIOD", "분석 시작일은 종료일보다 늦을 수 없습니다."),

    EXPENSE_ANALYSIS_FUTURE_PERIOD(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_FUTURE_PERIOD", "아직 시작하지 않은 기간은 분석할 수 없습니다."),

    /**
     * 챌린지 결과 분석은 최대 100일, 챌린지 상한이 바뀌면 이 값도 같이 봐야 하지만, Expense가 Challenge 상수를 참조하면
     * 의존 방향(Challenge → Expense)이 깨지므로 코드로 묶지 않는다. -> 환경변수로 Challenge 기간 변경도 고려할 만 하다
     */
    EXPENSE_ANALYSIS_PERIOD_TOO_LONG(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_PERIOD_TOO_LONG", "분석 기간은 최대 100일까지 조회할 수 있습니다."),

    EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED", "카테고리를 직접 입력한 경우 기존 카테고리와 명칭이 달라야 합니다."),

    EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED", "이유를 직접 입력한 경우 기존 제시된 이유와 달라야 합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
