package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChallengeErrorCode implements BaseErrorCode {

    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."),
    CHALLENGE_FORBIDDEN(HttpStatus.FORBIDDEN, "CHALLENGE_FORBIDDEN", "해당 챌린지에 접근 권한이 없습니다."),
    CHALLENGE_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_IN_PROGRESS", "이미 진행 중인 챌린지가 있습니다."),
    CHALLENGE_NOT_ENDED(HttpStatus.CONFLICT, "CHALLENGE_NOT_ENDED", "아직 진행 중인 챌린지입니다. (결과 미확정 — /current 사용)"),
    // give-up(#3)·adjust(#7) 공용 예정(명세 §0 매핑표) — 메시지를 특정 기능 문구로 좁히지 않는다
    CHALLENGE_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "CHALLENGE_NOT_IN_PROGRESS", "이미 종료된 챌린지입니다."),
    // 남은 횟수는 기간에 따라 1회 또는 2회라 메시지에 숫자를 박지 않는다 — 실제 값은 응답의 usedCount·maxCount로 간다
    ADJUSTMENT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "ADJUSTMENT_LIMIT_EXCEEDED", "한도 조정 가능 횟수를 모두 사용했습니다."),
    // 최종 종료 재요청과 종료 뒤 기록 수정이 같은 코드를 쓴다 — 클라가 처한 상황은 달라도 사실은 하나다
    CHALLENGE_ALREADY_CLOSED(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_CLOSED", "이미 최종 종료된 챌린지입니다."),
    CHALLENGE_NOT_CLOSABLE(HttpStatus.CONFLICT, "CHALLENGE_NOT_CLOSABLE", "중도 포기하거나 자동 취소된 챌린지는 최종 종료할 수 없습니다."),
    DAY_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "DAY_OUT_OF_RANGE", "날짜가 챌린지 기간 밖입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
