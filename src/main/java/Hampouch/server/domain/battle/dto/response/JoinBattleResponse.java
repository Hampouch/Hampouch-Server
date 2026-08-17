package Hampouch.server.domain.battle.dto.response;

import Hampouch.server.domain.battle.entity.Battle;

/**
 * POST /battles/invitations/{battleCode} 응답 — battleId 하나만 담는다.
 */
public record JoinBattleResponse(
        Long battleId
) {
    public static JoinBattleResponse from(Battle battle) {
        return new JoinBattleResponse(battle.getId());
    }
}
