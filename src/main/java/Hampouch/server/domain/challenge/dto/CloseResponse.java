package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDateTime;

/**
 * POST /api/challenges/{id}/close 응답 — 최종 종료 확정 결과.
 * 정기 확정 전에 기간이 끝난 IN_PROGRESS 상태가 남아 있으면 이 요청이 먼저 성패를 확정할 수 있어 status도 반환한다.
 */
public record CloseResponse(Long challengeId, ChallengeStatus status, LocalDateTime expenseLockedAt) {

    public static CloseResponse from(Challenge c) {
        return new CloseResponse(c.getId(), c.getStatus(), c.getExpenseLockedAt());
    }
}
