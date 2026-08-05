package Hampouch.server.domain.challenge.entity;

/**
 * 하루 단위 판정 결과: 그날 지출 합계 ≤ 하루 한도 이면 SUCCESS, 아니면 OVER.
 * FAIL이 아니라 OVER인 이유: "실패"는 챌린지 전체의 결말(ChallengeStatus.FAIL)에만 쓰는 말 —
 * 하루는 "한도를 넘었다"는 사실만 기록한다. 챌린지 성패는 기간 총지출로만 정해지므로(0727 PM 확정)
 * OVER는 달력 표시·overDays 집계에만 쓰이고 성패를 가르지 않는다.
 */
public enum DayStatus {
    SUCCESS,
    OVER
}
