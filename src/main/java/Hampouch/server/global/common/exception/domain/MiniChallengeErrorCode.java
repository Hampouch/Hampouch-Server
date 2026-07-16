package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// BaseErrorCode의 구현부는 여기 안 보인다 — @Getter가 컴파일 때 필드명대로 만든 getHttpStatus()/getCode()/getMessage()가
// 인터페이스 요구 메서드와 이름이 정확히 일치해 구현이 된다(나연 프레임워크의 의도된 정렬).
// 그래서 필드명을 바꾸면 만들어지는 getter 이름도 바뀌어 구현이 사라진다 — 컴파일 에러로 바로 잡힌다.
//
// 상수 하나 = 에러 응답 하나의 재료 3종. 채우는 손은 나연 GlobalExceptionHandler이고, 각 재료가 가는 곳은:
// httpStatus → 상태줄(HTTP/1.1 404 …) + 바디의 status 필드에 같은 숫자 복제 / code·message → 바디의 code·message.
// 그래서 새 에러가 필요하면 여기 상수 한 줄 추가 + 서비스에서 던지면 끝 — 응답 조립 코드는 쓸 일 없다.
@Getter
@RequiredArgsConstructor
public enum MiniChallengeErrorCode implements BaseErrorCode {

    // 명세 §3 확정 코드
    MINI_INVALID_BODY(HttpStatus.BAD_REQUEST, "MINI_INVALID_BODY", "요청 본문 형태가 올바르지 않습니다. (recommendedId 또는 custom 중 하나)"),
    MINI_INVALID_DURATION(HttpStatus.BAD_REQUEST, "MINI_INVALID_DURATION", "미니 챌린지 기간은 1·3·7·14·31일만 가능합니다."),
    // 명세 §5 확정 코드
    MINI_FUTURE_CHECK(HttpStatus.BAD_REQUEST, "MINI_FUTURE_CHECK", "미래 날짜는 체크할 수 없습니다."),
    // §1 미래 date 조회 차단은 확정 둘의 준용(0716 자체 확정 — 근거는 서비스 getDaily 주석), 코드명이 명세에 없어
    // MINI_FUTURE_CHECK 네이밍 계열로 자체 결정(안드 확인 시 맞춤)
    MINI_FUTURE_DATE(HttpStatus.BAD_REQUEST, "MINI_FUTURE_DATE", "미래 날짜는 조회할 수 없습니다."),
    // 코드명은 #1 DAY_OUT_OF_RANGE 네이밍을 따라 지음 — 0716 명세 §5에 반영·확정
    MINI_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "MINI_DATE_OUT_OF_RANGE", "날짜가 미니 챌린지 기간 밖입니다."),
    MINI_NOT_FOUND(HttpStatus.NOT_FOUND, "MINI_NOT_FOUND", "미니 챌린지를 찾을 수 없습니다."),
    // MINI_NOT_FOUND와 구분되는 카탈로그용 코드 — 0716 명세 §3에 반영·확정
    MINI_RECOMMENDED_NOT_FOUND(HttpStatus.NOT_FOUND, "MINI_RECOMMENDED_NOT_FOUND", "추천 미니 챌린지를 찾을 수 없습니다."),
    // 403 코드명은 #1 CHALLENGE_FORBIDDEN 네이밍 준수
    MINI_FORBIDDEN(HttpStatus.FORBIDDEN, "MINI_FORBIDDEN", "해당 미니 챌린지에 접근 권한이 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
