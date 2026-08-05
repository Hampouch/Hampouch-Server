package Hampouch.server.domain.challenge.entity;

/**
 * 목표 금액 조정 프리셋. 금액이 아니라 배율을 받는 이유는 클라와 서버가 각자 반올림하면 값이 갈라지기 때문이다.
 * 화면의 "현재 유지"는 API를 부르지 않는 선택이라 상수가 없고, "직접 입력"은 배율이 아니라 금액이라 여기 없다.
 *
 * 🔴 배율이 붙는 대상은 하루 한도가 아니라 **목표 금액**이다 — 조정 화면이 목표 금액 후보를 보여 주고
 * 하루 식비 목표는 고른 값에서 계산된 읽기 전용 표시다(14일·목표 140,000 → 20% → 168,000, 하루는 168,000 ÷ 14 = 12,000).
 */
public enum AdjustOption {

    PLUS_10(110),
    PLUS_20(120);

    /** 배율을 double(1.1)이 아니라 백분율 정수로 들고 있는 건 이진 부동소수 오차 회피다 — 140000 × 1.1은 154000.00000000003이 된다. */
    private final int percent;

    AdjustOption(int percent) {
        this.percent = percent;
    }

    /**
     * 조정 후 목표 금액 = 현재 목표 × 배율, 버림.
     * long으로 곱한 뒤 int 상한에서 자르는 건 오버플로 방어다 — 생성 요청의 budgetTotal에 상한이 없어
     * 목표가 int 최대에 가까우면 배율을 곱한 값이 int 범위를 넘고, 그대로 캐스팅하면 음수가 되어 조정이 500으로 나간다.
     */
    public int apply(int currentBudgetTotal) {
        long raised = (long) currentBudgetTotal * percent / 100;
        return (int) Math.min(raised, Integer.MAX_VALUE);
    }
}
