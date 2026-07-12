package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;

/** POST /api/v1/challenges 응답 (201 Created). */
public record CreateChallengeResponse(
        Long challengeId,
        int dailyLimit,
        LocalDate startDate,
        LocalDate endDate,
        ChallengeStatus status
) {
    public static CreateChallengeResponse from(Challenge c) {
        return new CreateChallengeResponse(
                c.getId(), c.getDailyLimit(), c.getStartDate(), c.getEndDate(), c.getStatus());
    }
}
