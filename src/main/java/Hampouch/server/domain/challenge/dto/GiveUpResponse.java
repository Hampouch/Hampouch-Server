package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

/**
 * POST /api/challenges/{id}/give-up 응답 — 포기 확정 결과 (API명세_중도포기.md).
 * 화면은 확정된 사실(어느 챌린지가, 어떤 상태로)만 필요해서 두 필드로 끝 —
 * 금액·기록 상세는 이후 결과 화면(GET /{id}/result)이 담당한다.
 */
public record GiveUpResponse(Long challengeId, ChallengeStatus status) {

    public static GiveUpResponse from(Challenge c) {
        return new GiveUpResponse(c.getId(), c.getStatus());
    }
}
