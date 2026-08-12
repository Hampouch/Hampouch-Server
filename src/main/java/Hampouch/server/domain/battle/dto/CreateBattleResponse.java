package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;

import java.time.LocalDate;

/** POST /battles 응답 — creatorId/creatorNickname은 화면에 불필요해서 제외 */
public record CreateBattleResponse(
        Long battleId,
        String battleCode,
        String title,
        int capacity,
        int durationDays,
        LocalDate startDate,
        LocalDate endDate,
        String penalty,
        BattleStatus status
) {
    public static CreateBattleResponse from(Battle battle) {
        return new CreateBattleResponse(
                battle.getId(),
                battle.getBattleCode(),
                battle.getTitle(),
                battle.getCapacity(),
                battle.getDurationDays(),
                battle.getStartDate(),
                battle.getEndDate(),
                battle.getPenalty(),
                battle.getStatus()
        );
    }
}
