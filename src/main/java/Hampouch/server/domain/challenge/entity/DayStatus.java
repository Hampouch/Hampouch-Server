package Hampouch.server.domain.challenge.entity;

/**
 * 하루 단위 판정 결과: 그날 지출 합계 ≤ 하루 한도 이면 SUCCESS, 아니면 OVER.
 * FAIL이 아니라 OVER인 이유: "실패"는 챌린지 전체의 결말(ChallengeStatus.FAIL)에만 쓰는 말 —
 * 하루는 "한도를 넘었다"는 사실만 기록하고, OVER 1일 이상이면 챌린지가 FAIL로 판정된다.
 */
public enum DayStatus {
    SUCCESS,
    OVER
}
