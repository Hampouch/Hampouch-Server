package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * POST /api/challenges/{id}/days 요청 — 테스트/시드 전용 엔드포인트.
 * category/emotion은 령준 지출(EXPENSE) 연동 형태 확정 후 사용 — 현재 미저장.
 */
public record DayUpsertRequest(

        @NotNull
        LocalDate date,

        @NotNull
        @Min(0)
        Integer spentAmount,

        String category,

        String emotion
) {
}
