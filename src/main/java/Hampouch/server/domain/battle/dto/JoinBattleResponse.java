package Hampouch.server.domain.battle.dto;

import Hampouch.server.domain.battle.entity.Battle;

/**
 * POST /battles/invitations/{battleCode} 응답 — battleId 하나만 담는다.
 * battleId는 Location 헤더로도 나가는데 body에 중복해서 담는 이유: 헤더만 두면 클라이언트가
 * URI 문자열을 직접 잘라 써야 해서, BASE_PATH가 바뀔 때 컴파일 에러 없이 런타임에 조용히 깨진다
 */
public record JoinBattleResponse(
        Long battleId
) {
    public static JoinBattleResponse from(Battle battle) {
        return new JoinBattleResponse(battle.getId());
    }
}
