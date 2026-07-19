package Hampouch.server.domain.minichallenge.dto;

import Hampouch.server.domain.minichallenge.entity.MiniChallenge;

import java.time.LocalDate;

/** POST /api/mini-challenges 응답 (201 Created, 명세 §3). */
public record CreateMiniChallengeResponse(
        Long miniChallengeId,
        String title,
        int durationDays,
        LocalDate startDate,
        LocalDate endDate
) {

    /** 엔티티 필드와 응답 필드의 대응 지식을 한곳에 모으는 매핑 통로(#1 CreateChallengeResponse.from과 같은 관례). */
    public static CreateMiniChallengeResponse from(MiniChallenge m) {
        return new CreateMiniChallengeResponse(
                m.getId(), m.getTitle(), m.getDurationDays(), m.getStartDate(), m.getEndDate());
    }
}
