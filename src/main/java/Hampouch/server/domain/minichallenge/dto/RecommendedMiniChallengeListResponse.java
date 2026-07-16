package Hampouch.server.domain.minichallenge.dto;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;

import java.util.List;

/**
 * GET /api/mini-challenges/recommended 응답 (API명세_미니챌린지.md §2).
 * items의 순서 = 서버가 섞어 준 랜덤 노출 순서(0630 확정) — 클라이언트는 받은 순서대로 그린다.
 */
public record RecommendedMiniChallengeListResponse(List<Item> items) {

    /** 추천 1건. recommendedId는 카탈로그 id — 미니 추가(명세 §3) 시 이 값으로 title/duration을 복사해 간다. */
    public record Item(Long recommendedId, String title, int durationDays) {

        public static Item from(RecommendedMiniChallenge preset) {
            return new Item(preset.getId(), preset.getTitle(), preset.getDurationDays());
        }
    }

    public static RecommendedMiniChallengeListResponse from(List<RecommendedMiniChallenge> presets) {
        return new RecommendedMiniChallengeListResponse(presets.stream().map(Item::from).toList());
    }
}
