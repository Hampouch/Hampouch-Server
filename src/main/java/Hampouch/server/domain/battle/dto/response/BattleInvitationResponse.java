package Hampouch.server.domain.battle.dto.response;

import Hampouch.server.domain.battle.entity.Battle;

import java.time.LocalDate;

/**
 * GET /battles/invitations/{battleCode} 응답 — battleId는 미포함
 * - battleId는 참가 시 response로 노출.
 */
public record BattleInvitationResponse(
        String title,
        String penalty,
        int capacity,
        int joinedCount,
        LocalDate startDate,
        int durationDays
) {
    public static BattleInvitationResponse from(Battle battle, int joinedCount) {
        return new BattleInvitationResponse(
                battle.getTitle(),
                battle.getPenalty(),
                battle.getCapacity(),
                joinedCount,
                battle.getStartDate(),
                battle.getDurationDays()
        );
    }
}
