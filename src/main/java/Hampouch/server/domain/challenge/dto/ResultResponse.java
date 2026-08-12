package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.expense.service.EmotionSpending;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/challenges/{id}/result 응답 — 종료 결과.
 *
 * emotionBreakdown은 ExpenseSpendingQuery.periodSpending()이 채운다
 * — 지출 분석 화면과 같은 record(EmotionSpending: enum + amount + 정수 ratio)를 그대로 써서 두 화면의 그래프 각도 계산 근거가
 * 어긋나지 않게 한다.
 * - 자체 EmotionRatio 및 categoryBreakDown(챌린지 현황 화면 미지원) 제거
 */
public record ResultResponse(
        Long challengeId,
        ChallengeStatus status,
        LocalDateTime closedAt,                   // 최종 종료 시각. null이면 아직 안 눌러 [지출 수정하기]·[챌린지 종료] 팝업을 띄울 상태
        Period period,
        Summary summary,
        List<EmotionSpending> emotionBreakdown    // 결과 화면 "소비 감정 분석" 그래프용
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
}
