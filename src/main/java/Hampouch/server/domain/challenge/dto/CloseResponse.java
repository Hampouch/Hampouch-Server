package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDateTime;

/**
 * POST /api/challenges/{id}/close 응답 — 최종 종료 확정 결과.
 * status를 함께 돌려주는 이유는 종료를 누르는 순간 미확정 챌린지의 성패가 정해지기 때문이다 —
 * 결과 화면을 안 열고 바로 눌렀다면 클라가 여기서 처음 성패를 받는다.
 */
public record CloseResponse(Long challengeId, ChallengeStatus status, LocalDateTime closedAt) {

    public static CloseResponse from(Challenge c) {
        return new CloseResponse(c.getId(), c.getStatus(), c.getClosedAt());
    }
}
