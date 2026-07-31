package Hampouch.server.domain.rest.dto;

import Hampouch.server.domain.rest.entity.UserRest;

import java.time.LocalDate;

/** POST /api/rests 응답 (201 Created) — 명세 §1. */
public record RestStartResponse(
        Long restId,
        LocalDate restStartDate,
        LocalDate plannedResumeDate
) {
    /** 엔티티→응답 매핑 규칙의 단일 출처(CreateChallengeResponse.from과 같은 역할). */
    public static RestStartResponse from(UserRest rest) {
        return new RestStartResponse(rest.getId(), rest.getRestStartDate(), rest.getPlannedResumeDate());
    }
}
