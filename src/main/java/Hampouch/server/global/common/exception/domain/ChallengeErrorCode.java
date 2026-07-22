package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChallengeErrorCode implements BaseErrorCode {

    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."),
    NO_ACTIVE_CHALLENGE(HttpStatus.NOT_FOUND, "NO_ACTIVE_CHALLENGE", "진행 중인 챌린지가 없습니다."),
    CHALLENGE_FORBIDDEN(HttpStatus.FORBIDDEN, "CHALLENGE_FORBIDDEN", "해당 챌린지에 접근 권한이 없습니다."),
    CHALLENGE_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_IN_PROGRESS", "이미 진행 중인 챌린지가 있습니다."),
    CHALLENGE_NOT_ENDED(HttpStatus.CONFLICT, "CHALLENGE_NOT_ENDED", "아직 진행 중인 챌린지입니다. (결과 미확정 — /current 사용)"),
    // give-up(#3)·adjust(#7) 공용 예정(명세 §0 매핑표) — 메시지를 특정 기능 문구로 좁히지 않는다
    CHALLENGE_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "CHALLENGE_NOT_IN_PROGRESS", "이미 종료된 챌린지입니다."),
    DAY_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "DAY_OUT_OF_RANGE", "날짜가 챌린지 기간 밖입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
