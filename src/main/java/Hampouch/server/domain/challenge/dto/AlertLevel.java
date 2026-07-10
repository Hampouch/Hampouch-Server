package Hampouch.server.domain.challenge.dto;

/**
 * 홈 경고 알림 수준 — 하루 사용률(todaySpent / dailyLimit) 기준(2026-07-07 확정).
 * 경계: 40% 미만 NONE / 40~70% CAUTION / 70% 이상 DANGER. 캐릭터 경계(30/70)와는 별개 축.
 * warningCards는 DANGER에서만 채운다.
 */
public enum AlertLevel {
    /** 알림 없음 — 사용률 40% 미만. */
    NONE,
    /** 주의 — 사용률 40~70%. */
    CAUTION,
    /** 위험 — 사용률 70% 이상(한도 초과 포함). */
    DANGER;

    public static AlertLevel of(double usageRate) {
        if (usageRate < 0.40) {
            return NONE;
        }
        if (usageRate < 0.70) {
            return CAUTION;
        }
        return DANGER;
    }
}
