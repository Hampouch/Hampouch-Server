package Hampouch.server.domain.battle.dto.response;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /battles/{battleId} 응답 — 배틀 메타데이터와 참가자 랭킹 통합.
 */
public record BattleDetailResponse(
        Long battleId,
        String battleCode,
        String title,
        String penalty,
        LocalDate startDate,
        LocalDate endDate,
        BattleStatus status,
        List<ParticipantRanking> participants,
        String penaltyTargetNickname //ONGOING, TERMINATE 시 필요, 나머지는 null
) {

    public record ParticipantRanking(
            Long userId,
            String nickname,
            String avatarUrl,
            Integer rank, // READY에서 null, ONGOING/TERMINATED에서만 등수를 매긴다.
            long todayAmount,
            long totalAmount,
            boolean isValid
    ) {
    }

    public static BattleDetailResponse from(Battle battle, List<ParticipantRanking> participants,
                                             String penaltyTargetNickname) {
        return new BattleDetailResponse(
                battle.getId(),
                battle.getBattleCode(),
                battle.getTitle(),
                battle.getPenalty(),
                battle.getStartDate(),
                battle.getEndDate(),
                battle.getStatus(),
                participants,
                penaltyTargetNickname
        );
    }
}
