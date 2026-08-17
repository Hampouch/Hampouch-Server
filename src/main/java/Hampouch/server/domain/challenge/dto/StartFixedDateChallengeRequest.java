package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record StartFixedDateChallengeRequest(
        @NotNull
        @Positive
        Long sourceChallengeId,

        @NotNull
        @FutureOrPresent
        LocalDate startDate,

        @NotNull
        @Min(0)
        @Max(Challenge.BUDGET_TOTAL_MAX)
        Integer budgetTotal,

        @NotNull
        @Min(1)
        @Max(31)
        Integer fixedDay
) {
}
