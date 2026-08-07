package Hampouch.server.domain.challenge.entity;

/**
 * 챌린지 종료 사유 — status(SUCCESS/FAIL)가 "어떻게" 정해졌는지의 표식.
 *
 * null = 기록에서 계산된 판정(종료 후 지출 수정 시 재계산 대상 — 0714 확정 규칙).
 * 컬럼 신설 전에 종료된 기존 행도 null이라, "null = 계산 판정"으로 읽으면
 * 별도 데이터 이관 없이 기존 데이터가 같은 의미로 흡수된다(ddl-auto=update 환경).
 *
 * GIVEN_UP = 유저 선언으로 확정된 FAIL(중도 포기, 0707 전체회의 "그만두면 실패").
 * 기록에서 나온 결과가 아니므로 지출을 수정해도 재계산으로 뒤집히지 않는다 —
 * upsertDay의 재계산 가드가 이 값을 본다(API명세_중도포기.md "재계산 부활 버그 방지").
 *
 * MISSING_DAILY_INPUT = 3일 연속 지출 미입력으로 자동 취소된 VOID.
 */
public enum EndReason {
    GIVEN_UP,
    MISSING_DAILY_INPUT
}
