package Hampouch.server.domain.rest.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/rests/resume 요청 — 복귀 팝업 세 선택지를 이 하나로 처리(명세 §2).
 * when이 자바 예약어 아니냐는 걱정은 불필요 — when은 키워드가 아니라 switch 패턴 매칭 안에서만
 * 특별 취급되는 문맥 한정 단어라 필드 이름으로 합법이고, JSON 필드명("when")도 명세 그대로 유지된다.
 */
public record RestResumeRequest(

        @NotNull
        ResumeWhen when,

        // EXTEND일 때만 쓰는 값이라 평소엔 null 허용 — 필수 여부는 아래 isExtendDaysPresent가 조건부로 검사.
        // 상한 3650은 기획값이 아니라 MySQL DATE 상한 초과 500을 막는 서버 방어값(잠정) — RestStartRequest.restDays와 동일
        @Min(1)
        @Max(3650)
        Integer extendDays
) {

    @AssertTrue(message = "더 쉬기(EXTEND)를 고르면 연장 일수(extendDays)를 입력해야 합니다.")
    public boolean isExtendDaysPresent() {
        return when != ResumeWhen.EXTEND || extendDays != null;
    }
}
