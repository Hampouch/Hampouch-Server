package Hampouch.server.domain.challenge.entity;

/**
 * 챌린지 전체 상태.
 * - IN_PROGRESS: 생성 직후, 진행 중
 * - SUCCESS: 종료(end_date 경과) 후 기간 총지출 ≤ 목표(budgetTotal) — 하루 한도를 넘긴 날이 있어도 무관(0727 PM 확정)
 * - FAIL: 종료 후 총지출 > 목표, 또는 중도 포기(EndReason.GIVEN_UP — 이쪽은 계산이 아니라 유저 선언)
 */
public enum ChallengeStatus {
    IN_PROGRESS,
    SUCCESS,
    FAIL
}
