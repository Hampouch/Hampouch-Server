package Hampouch.server.domain.minichallenge.dto;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/mini-challenges/{id}/check 요청 — PUT + 목표 상태(checked) 전달 = 멱등(명세 §5).
 * date는 생략 가능(생략 시 오늘) — 기본값 채움은 "지금"의 단일 출처인 서비스의 Clock이 담당.
 *
 * date를 LocalDate가 아닌 String으로 받는 이유: 형식이 틀린 값이 오면 LocalDate는 Jackson
 * 역직렬화 단계에서 터지는데, 그 예외는 나연 공통 핸들러의 Exception 폴백에 걸려 500이 된다.
 * 원시값으로 받아 서비스가 직접 파싱해 400으로 컷 — GET의 date 쿼리 파라미터와 같은 방어
 * (§0 공통 규약: 요청 형식 오류 = 400).
 */
public record MiniCheckRequest(

        String date,

        // checked는 명세상 필수. 전용 에러 코드가 지정되지 않아서(400만 명시) 빈 검증(@Valid)으로 처리 —
        // 누락 시 나연 공통 핸들러가 VALIDATION_ERROR 400을 내린다.
        @NotNull
        Boolean checked
) {
}
