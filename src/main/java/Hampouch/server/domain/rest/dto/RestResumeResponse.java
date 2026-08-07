package Hampouch.server.domain.rest.dto;

import Hampouch.server.domain.rest.entity.UserRest;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * POST /api/rests/resume 응답 (200 OK) — 명세 §2는 선택지에 따라 응답 필드가 다르다:
 * NOW/TOMORROW는 { restId, resumeDate }, EXTEND는 { restId, plannedResumeDate }.
 * 한 레코드에 두 모양을 담고 @JsonInclude(NON_NULL)로 null 필드를 직렬화에서 빼서
 * 각 모양만 내려가게 한다(ApiResponse가 data 생략에 쓰는 것과 같은 장치).
 * NON_NULL의 뜻: 값이 null인 속성은 키째 JSON에서 생략 — 기본값(ALWAYS)이면 "resumeDate": null 로 내려간다.
 * null만 보므로 빈 문자열·빈 리스트는 그대로 실리고(그건 NON_EMPTY), 역직렬화(JSON→객체)에는 영향이 없다.
 * 클래스에 붙였으니 세 필드 전부에 적용 — 필드만 골라 적용하려면 CurrentChallengeResponse처럼 컴포넌트에 붙인다.
 * 생성은 아래 두 정적 팩토리로만 — 어느 조합이 유효한지(둘 다 채움/둘 다 비움 금지)를 통로로 강제.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestResumeResponse(
        Long restId,
        LocalDate resumeDate,        // NOW/TOMORROW: 확정된 실제 복귀일
        LocalDate plannedResumeDate  // EXTEND: 미뤄진 복귀 예정일
) {

    /** NOW/TOMORROW — 복귀일이 확정된 응답. */
    public static RestResumeResponse resumed(UserRest rest, LocalDate resumeDate) {
        return new RestResumeResponse(rest.getId(), resumeDate, null);
    }

    /** EXTEND — 휴식이 계속되고 예정일만 미뤄진 응답. */
    public static RestResumeResponse extended(UserRest rest) {
        return new RestResumeResponse(rest.getId(), null, rest.getPlannedResumeDate());
    }
}
