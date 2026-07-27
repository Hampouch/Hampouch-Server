package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/challenges 요청 (온보딩 STEP2 목표 설정 완료).
 */
public record CreateChallengeRequest(

        // 상한 100일 = 0714 전체회의 확정(기간 입력의 한도 = 100일)
        @NotNull
        @Min(1)
        @Max(100)
        Integer durationDays,

        @NotNull
        @Min(1)
        Integer budgetTotal,

        @NotNull
        @FutureOrPresent
        LocalDate startDate,

        Boolean resetByPayday,

        @Min(1)
        @Max(31)
        Integer paydayDay,

        // 50자 = 저장 컬럼(challenge_weak_category.category) 길이 — 없으면 초과분이 저장 단계에서 500
        List<@NotBlank @Size(max = 50) String> weakCategories
) {

    public boolean resetByPaydayOrFalse() {
        return Boolean.TRUE.equals(resetByPayday);
    }

    /** resetByPayday=true면 paydayDay 필수. */
    @AssertTrue(message = "월급날 기준 리셋을 켜면 월급날(paydayDay)을 입력해야 합니다.")
    public boolean isPaydayConsistent() {
        return !resetByPaydayOrFalse() || paydayDay != null;
    }
}
