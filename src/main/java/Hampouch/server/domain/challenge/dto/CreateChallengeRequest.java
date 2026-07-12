package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/challenges 요청 (온보딩 STEP2 목표 설정 완료).
 */
public record CreateChallengeRequest(

        @NotNull
        @Min(1)
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

        List<@NotBlank String> weakCategories
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
