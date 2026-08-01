package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;

import java.time.LocalDate;

/**
 * GET /battles/invitations/{battleCode} 응답
 * 참가 가능 여부는 별도 필드가 아니라 에러(BATTLE_FULL/BATTLE_ALREADY_STARTED/ALREADY_JOINED/
 * BATTLE_CANCELLED)로 분기하므로, 이 레코드가 만들어졌다는 사실 자체가 참가 가능을 의미
 */
public record BattleInvitationResponse(
        String title,
        String penalty,
        int capacity,
        int joinedCount,
        LocalDate startDate,
        LocalDate endDate
) {
    public static BattleInvitationResponse from(Battle battle, int joinedCount) {
        return new BattleInvitationResponse(
                battle.getTitle(),
                battle.getPenalty(),
                battle.getCapacity(),
                joinedCount,
                battle.getStartDate(),
                battle.getEndDate()
        );
    }
}
