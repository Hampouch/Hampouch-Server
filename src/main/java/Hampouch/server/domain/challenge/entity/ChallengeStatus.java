package Hampouch.server.domain.challenge.entity;

/**
 * 챌린지 전체 상태.
 * - IN_PROGRESS: 생성 직후, 진행 중
 * - SUCCESS: 종료(end_date 경과) 후 모든 날 성공
 * - FAIL: 종료 후 초과한 날 1일 이상
 */
public enum ChallengeStatus {
    IN_PROGRESS,
    SUCCESS,
    FAIL
}
