package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;
import java.util.List;

/** GET /api/v1/challenges/current 응답 — 진행 중 챌린지 + 현황 + 홈 소비상태. */
public record CurrentChallengeResponse(
        ChallengeView challenge,
        Progress progress,
        Consumption consumption,
        List<String> warningCards,  // 홈에 띄울 경고 카드 코드 목록(alertLevel=DANGER에서만 채움). 현재 GOAL_TOO_TIGHT만 구현 · WEAK_CATEGORY_ALERT는 PM 기준 확정 대기
        Adjustment adjustment
) {
    /** 응답의 challenge 부분 — 엔티티 Challenge를 그대로 노출하지 않고 화면에 보일 필드만 담은 표현(내부 필드 은닉·응답 형태를 엔티티 변경과 분리). */
    public record ChallengeView(
            Long id,
            int durationDays,
            LocalDate startDate,
            LocalDate endDate,
            int budgetTotal,
            int dailyLimit,
            ChallengeStatus status
    ) {
    }

    /** 저장값이 아니라 ChallengeDay 집계(조회 시 계산). */
    public record Progress(
            int elapsedDays,      // 오늘이 챌린지 며칠째(시작일=1, 오늘 포함). 종료 후엔 종료일 기준으로 멈춤
            int remainingDays,    // 남은 일수(오늘 제외). elapsedDays + remainingDays = 전체 기간
            int successDays,
            int overDays,
            int currentStreak,
            int savedAmountSoFar  // 시작~오늘 누적 절약액 Σmax(0, 한도-지출). 결과 화면 최종 savedAmount와 구분해 "SoFar"
    ) {
    }

    /**
     * 홈 소비상태 — 하루 사용률 2축(2026-07-07 확정). usageRate = todaySpent / dailyLimit.
     * TODO(령준 지출 연동): todaySpent 출처 교체 — 연동 전엔 시드/POST /days 입력값.
     */
    public record Consumption(
            int todaySpent,
            int todayRemaining,  // = dailyLimit - todaySpent (파생값). 화면 표시용이라 계산 규칙을 서버 단일 출처로 두고 내려줌(클라 재계산 방지). 초과 시 음수
            int dailyLimit,
            double usageRate,
            ConsumptionCharacter character,
            AlertLevel alertLevel
    ) {
    }

    /** 한도 조정 사용/최대 횟수(챌린지당 최대 2회). */
    public record Adjustment(
            int usedCount,
            int maxCount
    ) {
    }
}
