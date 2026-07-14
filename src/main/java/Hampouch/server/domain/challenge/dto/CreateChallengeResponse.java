package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;

/** POST /api/challenges 응답 (201 Created). */
public record CreateChallengeResponse(
        Long challengeId,
        int dailyLimit,
        LocalDate startDate,
        LocalDate endDate,
        ChallengeStatus status
) {
    /**
     * 엔티티→응답 매핑 규칙의 단일 출처. Challenge.create처럼 생성 통로를 봉쇄하는 목적이 아니라
     * (public 레코드는 기본 생성자를 잠글 수 없음) 필드 대응 지식을 한곳에 모으고,
     * 변환 코드를 DTO 쪽에 둬서 엔티티가 DTO를 모르게 의존 방향을 지키는 용도.
     */
    public static CreateChallengeResponse from(Challenge c) {
        return new CreateChallengeResponse(
                c.getId(), c.getDailyLimit(), c.getStartDate(), c.getEndDate(), c.getStatus());
    }
}
