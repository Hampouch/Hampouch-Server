package Hampouch.server.domain.battle.dto;

import java.util.List;

/** GET /battles 응답 — 정렬 기준 startDate DESC, battleId DESC 고정 */
public record BattleListResponse(
        List<BattleSummary> battles
) {
}
