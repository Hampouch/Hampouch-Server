package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * expense 도메인 전용 에러 코드.
 * 다른 도메인의 ErrorCode를 재사용하지 않고 이 도메인 전용으로 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum ExpenseErrorCode implements BaseErrorCode {

    EXPENSE_NOT_FOUND(HttpStatus.NOT_FOUND, "EXPENSE_NOT_FOUND", "요청한 지출 내역을 찾을 수 없습니다."),

    EXPENSE_FORBIDDEN(HttpStatus.FORBIDDEN, "EXPENSE_FORBIDDEN", "해당 지출 내역에 접근 권한이 없습니다."),

    /**
     * 지출 변경 가능 날짜 범위를 벗어나 409 */
    EXPENSE_CHALLENGE_CLOSED(HttpStatus.CONFLICT, "EXPENSE_CHALLENGE_CLOSED", "이 날짜의 지출 기록은 지금 변경할 수 없습니다."),

    EXPENSE_ANALYSIS_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_INVALID_PERIOD", "분석 시작일은 종료일보다 늦을 수 없습니다."),

    EXPENSE_ANALYSIS_FUTURE_PERIOD(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_FUTURE_PERIOD", "아직 시작하지 않은 기간은 분석할 수 없습니다."),

    /**
     * 챌린지 결과 분석은 최대 100일, 챌린지 상한이 바뀌면 이 값도 같이 봐야 하지만, Expense가 Challenge 상수를 참조하면
     * 의존 방향(Challenge → Expense)이 깨지므로 코드로 묶지 않는다. -> 환경변수로 Challenge 기간 변경도 고려할 만 하다
     */
    EXPENSE_ANALYSIS_PERIOD_TOO_LONG(HttpStatus.BAD_REQUEST, "EXPENSE_ANALYSIS_PERIOD_TOO_LONG", "분석 기간은 최대 100일까지 조회할 수 있습니다."),

    EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED", "카테고리를 직접 입력한 경우 기존 카테고리와 명칭이 달라야 합니다."),

    EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED", "이유를 직접 입력한 경우 기존 제시된 이유와 달라야 합니다."),

    /** presigned URL은 발급됐지만 PATCH /expenses/{expenseId}/photos 시점에 S3 HeadObject로 확인해보니 실제 업로드가 안 된 imageKey인 경우. */
    EXPENSE_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "EXPENSE_IMAGE_NOT_UPLOADED", "업로드가 확인되지 않은 이미지입니다."),

    /** presign 요청의 size가 상한(10MB, community와 동일)을 넘는 경우 — contentLength 검증(ExpenseImageService) 전에 걸러낸다. */
    EXPENSE_IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "EXPENSE_IMAGE_SIZE_EXCEEDED", "이미지 크기는 최대 10MB까지 등록할 수 있습니다."),

    /** S3Presigner가 예상 못한 이유로 실패했거나 contentType이 지원 목록을 벗어난 경우 */
    EXPENSE_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EXPENSE_IMAGE_UPLOAD_FAILED", "이미지 업로드 처리 중 오류가 발생했습니다."),

    /** imageKey가 이 요청자의 접두어(expenses/{userId}/...)로 시작하지 않는 경우 — 남의 presign 응답을 흉내낸 시도(#4). */
    EXPENSE_IMAGE_KEY_FORBIDDEN(HttpStatus.FORBIDDEN, "EXPENSE_IMAGE_KEY_FORBIDDEN", "본인이 발급받은 이미지만 사용할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}