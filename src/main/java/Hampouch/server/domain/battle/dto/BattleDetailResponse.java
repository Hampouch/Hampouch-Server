package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /battles/{battleId} 응답 — 배틀 메타데이터와 참가자 랭킹을 하나로 합침.
 * penaltyTargetNickname: ONGOING 상세엔 "현재 꼴찌 : {닉네임}"이,
 * TERMINATED 결과 화면엔 "{닉네임} 님이 벌칙을 수행해요"가 각각 노출됨
 * 진행 중/종료 둘 다 벌칙 대상자가 필요해서 상태 무관하게 필드 하나로 통일.
 * READY/CANCELLED의 경우 null.
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
        String penaltyTargetNickname
) {

    /**
     * rank는 READY에서 null, ONGOING/TERMINATED에서만 값이 참 - BattleService가 상태별로 판단.
     * todayAmount는 TERMINATED에서 0 고정
     * isValid는 BattleParticipant.isValid 그대로 노출 — 3일 연속 미기록 무효화 배치(④, 아직 미구현)
     * 반영 시 바로 응답에 실리도록 지금 필드부터 추가해둠.
     */
    public record ParticipantRanking(
            Long userId,
            String nickname,
            String avatarUrl,
            Integer rank,
            int todayAmount,
            int totalAmount,
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
