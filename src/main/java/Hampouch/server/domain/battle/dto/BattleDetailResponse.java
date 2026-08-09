package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /battles/{battleId} 응답 — 배틀 메타데이터와 참가자 랭킹을 하나로 합침
 * 26.05.01 - 26.05.07 (7일)와 같이 날짜범위와 기간을 같이 보여줌.
 * battleCode는 READY 상세 화면의 링크 다시 복사하기 버튼을 근거로 포함
 */
public record BattleDetailResponse(
        Long battleId,
        String battleCode,
        String title,
        String penalty,
        int capacity,
        LocalDate startDate,
        LocalDate endDate,
        int durationDays,
        BattleStatus status,
        List<ParticipantRanking> participants
) {

    /**
     * rank는 READY에서 null, ONGOING/TERMINATED에서만 값이 참 - BattleService가 상태별로 판단
     * todayAmount는 TERMINATED에서 0 고정 — 배틀 종료 후엔
     * 오늘 개념이 무의미(확정된 디자인 근거는 아직 없음, ③ 착수 시 임시 결정).
     */
    public record ParticipantRanking(
            Long userId,
            String nickname,
            String avatarUrl,
            Integer rank,
            int todayAmount,
            int totalAmount
    ) {
    }

    public static BattleDetailResponse from(Battle battle, List<ParticipantRanking> participants) {
        return new BattleDetailResponse(
                battle.getId(),
                battle.getBattleCode(),
                battle.getTitle(),
                battle.getPenalty(),
                battle.getCapacity(),
                battle.getStartDate(),
                battle.getEndDate(),
                battle.getDurationDays(),
                battle.getStatus(),
                participants
        );
    }
}
