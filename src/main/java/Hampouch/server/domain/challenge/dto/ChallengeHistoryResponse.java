package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/history 응답 — 지난(종료된) 챌린지 리스트 (마이페이지, 명세 API명세_히스토리.md).
 *
 * 종료된 챌린지가 없으면 items는 빈 배열 — 아직 한 번도 끝내 본 적 없는 유저의 정상 응답이라 404가 아니다.
 */
public record ChallengeHistoryResponse(List<Item> items) {

    /**
     * 리스트 한 줄(카드 하나). status는 종료 시점에 확정 저장된 값 그대로,
     * 금액 요약(actualSpent·savedAmount)은 저장값이 아니라 조회 시 집계하며 포기한 날 이후는 제외한다.
     */
    public record Item(
            Long challengeId,
            ChallengeStatus status,
            LocalDate startDate,
            LocalDate endDate,
            int durationDays,
            int budgetTotal,   // 생성 때 입력한 기간 전체 총예산(저장값) — dailyLimit은 이 값/기간(버림)의 스냅샷
            int actualSpent,   // 목표 종료일 또는 포기 전날까지의 실지출 합계
            int savedAmount    // 같은 구간의 일별 max(0, 한도-지출) 합
    ) {
        /**
         * 엔티티+집계값 → 카드 매핑의 단일 출처. 집계 결과는 서비스가 계산해 값으로 넘긴다 —
         * 집계 record(ChallengeSummary)는 service 패키지 내부 계산용이라 dto가 직접 물지 않는다.
         */
        public static Item of(Challenge c, int actualSpent, int savedAmount) {
            return new Item(c.getId(), c.getStatus(), c.getStartDate(), c.getEndDate(),
                    c.getDurationDays(), c.getBudgetTotal(), actualSpent, savedAmount);
        }
    }
}
