package Hampouch.server.domain.challenge.dto;

/**
 * 홈 경고 알림 수준 — 오늘 하루 전체 지출의 사용률(todaySpent / dailyLimit) 기준.
 * 경계: 30% 미만 NONE / 30~70% CAUTION / 70% 이상 DANGER — 0714 캐릭터 경계(30/70)와 통일(구 40/70).
 * 경계 숫자는 같아졌지만 캐릭터(표정)와 알림(경고 등급)은 역할이 달라 필드를 분리 유지 — 디자인이 다시 갈라져도 응답 계약이 안 바뀌게.
 * 집중 카테고리 주의 카드(WEAK_CATEGORY_ALERT)와 혼동 주의 — 그쪽은 카테고리 누적 지출 ÷ 전체 예산이 70% 이상일 때(0714 확정)로,
 * 분자·분모·기간이 전부 다른 별개 신호다. warningCards도 이 등급과 무관하게 카드별 자기 트리거로 발동(0713 게이트 제거).
 *
 * dto 패키지에 있는 이유: DB에 저장되지 않고 매 요청 usageRate로 즉석 계산돼 응답 JSON에만 실리는
 * "API 응답 어휘"라서. 같은 enum이라도 컬럼으로 저장되는 DayStatus가 entity 쪽에 있는 것과 대비.
 */
public enum AlertLevel {
    /** 알림 없음 — 사용률 30% 미만. */
    NONE,
    /** 주의 — 사용률 30~70%. */
    CAUTION,
    /** 위험 — 사용률 70% 이상(한도 초과 포함). */
    DANGER;

    public static AlertLevel of(double usageRate) {
        if (usageRate < 0.30) {
            return NONE;
        }
        if (usageRate < 0.70) {
            return CAUTION;
        }
        return DANGER;
    }
}
