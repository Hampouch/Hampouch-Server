package Hampouch.server.domain.challenge.dto;

/**
 * 홈 캐릭터 표정 — 하루 사용률(todaySpent / dailyLimit) 기준(2026-07-07 확정).
 * 경계: 30% 미만 FULL / 30~70% NORMAL / 70% 이상 SKINNY. 알림 경계(40/70)와는 별개 축.
 * enum 상수명은 자체 결정 — 안드 확인 시 맞춤.
 */
public enum ConsumptionCharacter {
    /** 볼빵빵 — 사용률 30% 미만. */
    FULL,
    /** 보통 — 사용률 30~70%. */
    NORMAL,
    /** 홀쭉 — 사용률 70% 이상(한도 초과 포함). */
    SKINNY;

    public static ConsumptionCharacter of(double usageRate) {
        if (usageRate < 0.30) {
            return FULL;
        }
        if (usageRate < 0.70) {
            return NORMAL;
        }
        return SKINNY;
    }
}
