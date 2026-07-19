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

    // 상태코드 규칙(앱 공통): 대상이 있는데 동작과 상태가 안 맞으면 409, 대상 자체가 없으면 404.
    // - REST_ALREADY_ACTIVE(409): 휴식을 시작하려는데 이미 열린 휴식이 존재 = 충돌(CHALLENGE_ALREADY_IN_PROGRESS와 같은 갈래).
    // - REST_NOT_ACTIVE(404): "찾을 수 없는 대상" = 복귀가 findActiveOn으로 찾는 이 유저의 '열린 user_rest 행'.
    //   URL(/api/rests/resume)엔 id가 없고 대상은 "유저 + 동시 1건 불변식"으로 암묵 지정된다 — 그 행이 없으면
    //   (findActiveOn이 빈 Optional) 작업할 대상 자체가 없어 404. "휴식 중이 아님"과 "그 행이 없음"은 같은 말.
    //   챌린지의 NO_ACTIVE_CHALLENGE(404, /current에 id 없이 '진행 중 챌린지 행 없음')를 대칭 이식 — 활성 대상 없음은 앱 전체가 404.
    //   (요청 입력은 멀쩡하고 서버 상태가 없는 것뿐이라 400은 부적합 — 400은 입력 오류용.)
    REST_ALREADY_ACTIVE(HttpStatus.CONFLICT, "REST_ALREADY_ACTIVE", "이미 휴식 중입니다."),
    REST_NOT_ACTIVE(HttpStatus.NOT_FOUND, "REST_NOT_ACTIVE", "휴식 중이 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
