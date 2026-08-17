package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/current의 날짜 기준 챌린지 현황 응답.
 * 선택 날짜에 챌린지가 없으면 {@code "challenge": null}이고 나머지 현황 블록은 생략한다.
 */
public record CurrentChallengeResponse(
        ChallengeView challenge,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Progress progress,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Consumption consumption,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<WarningCard> warningCards,  // 홈 경고 카드 목록 — 현재 구현값이 없어 []이며 GOAL_TOO_TIGHT 복원(#224)을 위해 필드 유지
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExpenseInputState expenseInputState,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Adjustment adjustment
) {

    /** 선택 날짜에 챌린지가 있는 상태. */
    public static CurrentChallengeResponse forChallenge(ChallengeView challenge, Progress progress,
                                                        Consumption consumption, List<WarningCard> warningCards,
                                                        ExpenseInputState expenseInputState, Adjustment adjustment) {
        return new CurrentChallengeResponse(
                challenge, progress, consumption, warningCards, expenseInputState, adjustment);
    }

    /** 선택 날짜에 챌린지가 없는 상태. */
    public static CurrentChallengeResponse empty() {
        return new CurrentChallengeResponse(null, null, null, null, null, null);
    }

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

    /**
     * 미기록일은 지출액 0원으로 계산하되, 입력 완료 여부는 별도 경고·무효 규칙으로 판정한다.
     * 선택 날짜에 기록이 없어도 그날은 지출액 0원으로 진행 현황에 포함한다.
     */
    public record Progress(
            int elapsedDays,
            int remainingDays,
            int successDays,
            int overDays,
            int currentStreak,
            int savedAmountSoFar
    ) {
    }

    /**
     * 홈 소비상태 — 선택 날짜의 하루 사용률. 기존 필드명 todaySpent·todayRemaining은 호환을 위해 유지한다.
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
