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
    PLUS_20(120),
    PLUS_30(130);

    /** 배율을 double(1.1)이 아니라 백분율 정수로 들고 있는 건 이진 부동소수 오차 회피다 — 140000 × 1.1은 154000.00000000003이 된다. */
    private final int percent;

    AdjustOption(int percent) {
        this.percent = percent;
    }

    /**
     * 조정 후 목표 금액 = 현재 목표 × 배율, 버림.
     * long으로 곱하는 건 곱셈 자체가 int 범위를 넘지 않게 하는 것이고, 되돌릴 때 잘리지 않는 근거는
     * 요청 상한이다 — 생성·직접 입력 모두 Challenge.BUDGET_TOTAL_MAX(10,000,000)에서 막히고 조정은 최대 2회라
     * 도달 가능한 목표는 16,900,000(= 10,000,000 × 1.3²)이 최대다.
     * 상한 상향 등으로 그 근거가 깨지면 toIntExact가 그 자리에서 터진다 — 맨 캐스팅으로 두면 음수로 뒤집힌 뒤
     * Challenge.adjustGoal의 음수 가드에 걸려 오버플로가 "budgetTotal은 0 이상" 메시지로 가려진다.
     */
    public int apply(int currentBudgetTotal) {
        return Math.toIntExact((long) currentBudgetTotal * percent / 100);
    }
}
