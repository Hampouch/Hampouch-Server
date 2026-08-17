package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/challenges 요청 (온보딩 STEP2 목표 설정 완료).
 */
public record CreateChallengeRequest(

        // 상한 100일 = 0714 전체회의 확정(기간 입력의 한도 = 100일)
        @Min(1)
        @Max(100)
        Integer durationDays,

        // 0원 = 무지출 챌린지라 하한이 0이다(0727 자체 결정). 상한이 없으면 int 최대까지 통과한다 — 상한 값의 유도는 Challenge.BUDGET_TOTAL_MAX
        @NotNull
        @Min(0)
        @Max(Challenge.BUDGET_TOTAL_MAX)
        Integer budgetTotal,

        @NotNull
        @FutureOrPresent
        LocalDate startDate,

        @Min(1)
        @Max(31)
        Integer fixedDay,

        // 이름 하나당 50자 = 저장 컬럼(challenge_weak_category.category) 길이 — 없으면 51자짜리 이름 하나에 저장이 터져 500
        List<@NotBlank @Size(max = 50) String> weakCategories
) {
    @AssertTrue(message = "기간과 고정일 중 하나만 입력해야 합니다.")
    public boolean isSelectionConsistent() {
        return (durationDays == null) != (fixedDay == null);
    }
}
