package Hampouch.server.domain.minichallenge.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * PUT /api/mini-challenges/{id}/check 요청 — PUT + 목표 상태(checked) 전달 = 멱등(명세 §5).
 * date는 생략 가능(생략 시 오늘) — 기본값 채움은 "지금"의 단일 출처인 서비스의 Clock이 담당.
 */
public record MiniCheckRequest(

        LocalDate date,

        // checked는 명세상 필수. 전용 에러 코드가 지정되지 않아서(400만 명시) 빈 검증(@Valid)으로 처리 —
        // 누락 시 나연 공통 핸들러가 VALIDATION_ERROR 400을 내린다.
        @NotNull
        Boolean checked
) {
}
