package Hampouch.server.domain.challenge.dto;

import java.time.LocalDate;

public record NextFixedDateChallengeResponse(
        Long sourceChallengeId,
        int fixedDay,
        LocalDate startDate,
        LocalDate endDate,
        int durationDays,
        int budgetTotal,
        int dailyLimit
) {
}
