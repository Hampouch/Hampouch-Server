package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.DayStatus;

import java.time.LocalDate;

/** POST /api/v1/challenges/{id}/days 응답 (200 OK). */
public record DayUpsertResponse(
        LocalDate date,
        int spentAmount,
        int dailyLimit,
        DayStatus status
) {
}
