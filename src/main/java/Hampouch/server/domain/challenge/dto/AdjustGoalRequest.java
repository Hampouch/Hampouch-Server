package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.AdjustOption;
import Hampouch.server.domain.challenge.entity.Challenge;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * POST /api/challenges/{id}/adjust 요청.
 * 진행 중 조정 화면은 10%·20%·30% 여유 프리셋을 제공하며 직접 입력 요청도 지원한다.
 * "현재 유지"는 조정이 아니므로 API를 호출하지 않는다.
 * option이 PLUS_10/PLUS_20/PLUS_30이 아닌 문자열이면 역직렬화 단계에서 걸려 400이 된다.
 */
public record AdjustGoalRequest(

        AdjustOption option,

        /** 직접 입력 금액(기간 전체 목표). 하한·상한은 생성 요청의 budgetTotal과 같은 값으로 맞춘다. */
        @Min(0)
        @Max(Challenge.BUDGET_TOTAL_MAX)
        Integer budgetTotal
) {

    /**
     * 프리셋과 직접 입력 중 정확히 하나만 받는다 — 둘 다 없으면 무엇으로 조정할지 알 수 없고,
     * 둘 다 있으면 어느 쪽이 이기는지가 계약에 없어 클라마다 다르게 해석된다.
     */
    @AssertTrue(message = "조정 옵션(option)과 직접 입력 금액(budgetTotal) 중 하나만 보내야 합니다.")
    public boolean isExactlyOneChoice() {
        return (option != null) ^ (budgetTotal != null);
    }
}
