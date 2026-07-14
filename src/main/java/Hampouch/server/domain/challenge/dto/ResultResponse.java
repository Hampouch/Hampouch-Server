package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/{id}/result 응답 — 종료 결과.
 *
 * TODO(령준 지출 연동): categoryBreakdown/emotionBreakdown 채우기 — EXPENSE(외부) 의존이라
 * 그전(Phase 1)에는 빈 배열로 둔다.
 */
public record ResultResponse(
        Long challengeId,
        ChallengeStatus status,
        Period period,
        Summary summary,
        List<CategoryAmount> categoryBreakdown,  // 결과 화면 "카테고리별 지출 금액" 그래프용 (배달 38400, 카페 23500…). 령준 연동 전엔 빈 배열
        List<EmotionRatio> emotionBreakdown      // 결과 화면 "감정별 지출 비율" 그래프용 (충동 0.42, 스트레스 0.31…). 령준 연동 전엔 빈 배열
) {
    public record Period(
            LocalDate startDate,
            LocalDate endDate,
            int durationDays
    ) {
        /** 엔티티→기간 블록 매핑의 단일 출처 — 재료가 Challenge 하나뿐이라 from 규칙(엔티티 하나로 완결되는 변환은 DTO 안으로) 적용. */
        public static Period from(Challenge c) {
            return new Period(c.getStartDate(), c.getEndDate(), c.getDurationDays());
        }
    }

    public record Summary(
            int successDays,
            int overDays,
            int savedAmount,
            int overAmount,
            int maxStreak,
            int budgetTotal,
            int actualSpent
    ) {
    }

    /** categoryBreakdown의 원소 — 카테고리 이름 + 그 카테고리에 쓴 금액(원). */
    public record CategoryAmount(
            String category,
            int amount
    ) {
    }

    /** emotionBreakdown의 원소 — 감정 이름 + 그 감정으로 쓴 지출의 비율(0~1, 합 1.0). */
    public record EmotionRatio(
            String emotion,
            double ratio
    ) {
    }
}
