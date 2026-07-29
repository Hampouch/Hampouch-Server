package Hampouch.server.domain.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/rests 요청 — 휴식 시작. 화면의 3일/1주/2주/직접선택은 클라가 일수로 통일해 보낸다(명세 §1).
 */
public record RestStartRequest(

        // 기획 상한은 없음(명세 §1 자체 결정 — 제한이 생기면 값 교체). 3650(10년)은 정책이 아니라 서버 방어값(잠정):
        // 너무 큰 일수는 복귀 예정일이 MySQL DATE 상한(9999-12-31)을 넘어 INSERT 단계 500이 되므로
        // 상식 밖 값을 400으로 컷한다 — 캘린더의 연도 1~9999 가드와 같은 원칙(클라 검증은 UX용, 서버가 최종 방어선)
        @NotNull
        @Min(1)
        @Max(3650)
        Integer restDays
) {
}
