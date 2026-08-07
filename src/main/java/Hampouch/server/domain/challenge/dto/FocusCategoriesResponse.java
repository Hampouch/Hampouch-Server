package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeWeakCategory;

import java.util.List;

/** PUT /api/challenges/{id}/focus-categories 응답 (200 OK) — 교체가 끝난 최종 목록. */
public record FocusCategoriesResponse(
        Long challengeId,
        List<String> categories
) {
    public static FocusCategoriesResponse from(Challenge c) {
        return new FocusCategoriesResponse(
                c.getId(),
                c.getWeakCategories().stream().map(ChallengeWeakCategory::getCategory).toList());
    }
}
