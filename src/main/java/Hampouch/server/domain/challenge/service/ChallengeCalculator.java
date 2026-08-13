package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import Hampouch.server.domain.challenge.entity.EndReason;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChallengeCalculator {

    public static int dailyLimit(int budgetTotal, int durationDays) {
        return budgetTotal / durationDays;
    }

    // PM 확인 전 잠정 기준으로 모든 종료 결과의 기간을 유지한다.
    public static int recommendedDurationDays(int durationDays) {
        return durationDays;
    }

    public static int recommendedBudgetTotal(ChallengeStatus status, EndReason endReason,
                                             int budgetTotal, int actualSpent) {
        if (status == ChallengeStatus.SUCCESS) {
            if (actualSpent > budgetTotal) {
                throw new IllegalStateException("성공한 챌린지의 실지출이 목표 금액을 초과했습니다.");
            }
            return (int) (budgetTotal * 9L / 10);
        }
        if (status == ChallengeStatus.VOID || endReason == EndReason.GIVEN_UP) {
            return budgetTotal;
        }
        if (status == ChallengeStatus.FAIL) {
            if (actualSpent <= budgetTotal) {
                throw new IllegalStateException("금액 초과로 실패한 챌린지의 실지출이 목표 금액 이하입니다.");
            }
            int overAmount = actualSpent - budgetTotal;
            return budgetTotal + overAmount / 2 + overAmount % 2;
        }
        throw new IllegalArgumentException("종료되지 않은 챌린지는 추천할 수 없습니다: " + status);
    }

    public static DayStatus judge(int spentAmount, int dailyLimit) {
        return spentAmount <= dailyLimit ? DayStatus.SUCCESS : DayStatus.OVER;
    }

    /**
     * 미입력일은 0원 지출·SUCCESS로 집계한다. 기록이 있는 날은 저장된 한도 스냅샷을 사용해
     * 이후 목표 조정이 지난 기록에 소급되지 않게 한다.
     */
    public static ChallengeSummary summarizeForResult(List<ChallengeDay> days, DailyLimitTimeline limits,
                                                      LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, ChallengeDay> byDate = new HashMap<>();
        for (ChallengeDay d : days) {
            byDate.put(d.getDayDate(), d);
        }

        int successDays = 0;
        int overDays = 0;
        int savedAmount = 0;
        int overAmount = 0;
        int actualSpent = 0;
        int maxStreak = 0;
        int currentStreak = 0;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            ChallengeDay d = byDate.get(date);
            int spent = d == null ? 0 : d.getSpentAmount();
            DayStatus status = d == null ? DayStatus.SUCCESS : d.getStatus();
            int dailyLimit = dailyLimitOf(d, date, limits);
            actualSpent += spent;
            savedAmount += Math.max(0, dailyLimit - spent);
            overAmount += Math.max(0, spent - dailyLimit);
            if (status == DayStatus.SUCCESS) {
                successDays++;
                currentStreak++;
                maxStreak = Math.max(maxStreak, currentStreak);
            } else {
                overDays++;
                currentStreak = 0;
            }
        }
        return new ChallengeSummary(successDays, overDays, savedAmount, overAmount, maxStreak, actualSpent);
    }

    private static int dailyLimitOf(ChallengeDay day, LocalDate date, DailyLimitTimeline limits) {
        return day == null ? limits.on(date) : day.getDailyLimit();
    }

    private static final int SHORT_CHALLENGE_MAX_DAYS = 14;

    public static int maxAdjustmentCount(int durationDays) {
        return durationDays <= SHORT_CHALLENGE_MAX_DAYS ? 1 : 2;
    }

    public static int currentStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingStreakAsOf(days, startDate, lastJudgedDate, DayStatus.SUCCESS);
    }

    /** 한도 0원일 때 Infinity·NaN이 응답에 노출되지 않도록 초과 여부만 반환한다. */
    public static double usageRate(int todaySpent, int dailyLimit) {
        if (dailyLimit <= 0) {
            return todaySpent > 0 ? 1.0 : 0.0;
        }
        return (double) todaySpent / dailyLimit;
    }

    public static int trailingOverStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingStreakAsOf(days, startDate, lastJudgedDate, DayStatus.OVER);
    }

    private static final int GOAL_TOO_TIGHT_MIN_STREAK = 3;

    public static boolean isGoalTooTight(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingOverStreakAsOf(days, startDate, lastJudgedDate) >= GOAL_TOO_TIGHT_MIN_STREAK;
    }

    /** 미기록일은 SUCCESS로 간주해 성공 스트릭을 잇고 초과 스트릭을 끊는다. */
    private static int trailingStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate,
                                          DayStatus target) {
        Map<LocalDate, ChallengeDay> byDate = new HashMap<>();
        for (ChallengeDay d : days) {
            byDate.put(d.getDayDate(), d);
        }
        int streak = 0;
        for (LocalDate date = lastJudgedDate; !date.isBefore(startDate); date = date.minusDays(1)) {
            ChallengeDay d = byDate.get(date);
            DayStatus status = d == null ? DayStatus.SUCCESS : d.getStatus();
            if (status != target) {
                break;
            }
            streak++;
        }
        return streak;
    }

    public static ChallengeStatus resultStatus(int actualSpent, int budgetTotal) {
        return actualSpent > budgetTotal ? ChallengeStatus.FAIL : ChallengeStatus.SUCCESS;
    }

}
