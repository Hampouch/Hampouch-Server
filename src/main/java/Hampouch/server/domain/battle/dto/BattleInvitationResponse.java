package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;

import java.time.LocalDate;

/**
 * GET /battles/invitations/{battleCode} 응답 — battleId는 포함하지 않는다(참가 전 미리보기라
 * battleId 리소스는 참가자 전용이라는 원칙, hampouch_battle_api_review 확정 사항). 참가 가능
 * 여부는 별도 필드가 아니라 에러(BATTLE_FULL/BATTLE_ALREADY_STARTED/ALREADY_JOINED/
 * BATTLE_CANCELLED)로 분기하므로, 이 레코드가 만들어졌다는 사실 자체가 "참가 가능"을 의미한다.
 *
 * endDate 대신 durationDays인 이유(2026-08-01, 초대 링크 진입 화면 Figma 확인): 화면이
 * "종료일"을 따로 안 보여주고 "7일 · 5인"처럼 durationDays(3/7/14/31 프리셋)를 그대로 노출한다.
 * startDate는 화면에 아직 없지만 디자이너에게 명시 요청 예정(반영 가능성 높음)이라 미리 유지한다.
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
