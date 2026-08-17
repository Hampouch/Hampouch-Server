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
     * 금액·진행도 집계에서는 기록이 없는 날의 지출액을 0원으로 계산한다.
     * 따라서 그날은 SUCCESS이고 하루 한도 전액이 절약액에 더해지며 성공 스트릭도 이어진다.
     * 입력 완료 여부는 별도 규칙이라, 연속 미입력은 ChallengeService에서 경고·무효 처리한다.
     * 기록이 있는 날은 저장된 한도 스냅샷을 사용해 이후 목표 조정이 지난 기록에 소급되지 않게 한다.
     */
    public static ChallengeSummary summarizeThrough(List<ChallengeDay> days, DailyLimitTimeline limits,
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

    /**
     * summarizeThrough의 Expense 기반 버전 — 날짜별 지출 합계 맵을 받아 한도·판정을 계산
     * DailyLimitTimeline.on(date)는 조정 이력만으로 결정되는 순수 함수라 얼려둘 필요가 없어 항상 limits.on(date)를 쓴다.
     */
    public static ChallengeSummary summarizeThrough(Map<LocalDate, Integer> spentByDate, DailyLimitTimeline limits,
                                                     LocalDate startDate, LocalDate endDate) {
        int successDays = 0;
        int overDays = 0;
        int savedAmount = 0;
        int overAmount = 0;
        int actualSpent = 0;
        int maxStreak = 0;
        int currentStreak = 0;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            int spent = spentByDate.getOrDefault(date, 0);
            int dailyLimit = limits.on(date);
            DayStatus status = judge(spent, dailyLimit);
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

    private static final int SHORT_CHALLENGE_MAX_DAYS = 14;

    public static int maxAdjustmentCount(int durationDays) {
        return durationDays <= SHORT_CHALLENGE_MAX_DAYS ? 1 : 2;
    }

    /** 집계 종료일부터 거꾸로 센 연속 성공일 수. 미기록일도 0원 SUCCESS로 계산한다. */
    public static int currentStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate aggregationEndDate) {
        return trailingStreakAsOf(days, startDate, aggregationEndDate, DayStatus.SUCCESS);
    }

    /**
     * currentStreakAsOf의 Expense 기반 버전.
     * 이 버전은 status가 없으므로 judge(spent, limits.on(date))로 그 자리에서 계산 — limits 파라미터가 필요
     */
    public static int currentStreakAsOf(Map<LocalDate, Integer> spentByDate, DailyLimitTimeline limits,
                                        LocalDate startDate, LocalDate aggregationEndDate) {
        int streak = 0;
        for (LocalDate date = aggregationEndDate; !date.isBefore(startDate); date = date.minusDays(1)) {
            int spent = spentByDate.getOrDefault(date, 0);
            if (judge(spent, limits.on(date)) != DayStatus.SUCCESS) {
                break;
            }
            streak++;
        }
        return streak;
    }

    /** 한도 0원일 때 Infinity·NaN이 응답에 노출되지 않도록 초과 여부만 반환한다. */
    public static double usageRate(int todaySpent, int dailyLimit) {
        if (dailyLimit <= 0) {
            return todaySpent > 0 ? 1.0 : 0.0;
        }
        return (double) todaySpent / dailyLimit;
    }

    /** 집계 종료일부터 거꾸로 센 연속 초과일 수. 미기록일은 0원 SUCCESS라 연속 초과를 끊는다. */
    public static int trailingOverStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate aggregationEndDate) {
        return trailingStreakAsOf(days, startDate, aggregationEndDate, DayStatus.OVER);
    }

    /** 목표 조정 알림 발송 기준 — 마지막 3일 연속 한도 초과. */
    private static final int GOAL_ADJUSTMENT_NOTIFICATION_MIN_STREAK = 3;

    public static boolean meetsGoalAdjustmentNotificationCondition(List<ChallengeDay> days, LocalDate startDate,
                                                                   LocalDate aggregationEndDate) {
        return trailingOverStreakAsOf(days, startDate, aggregationEndDate)
                >= GOAL_ADJUSTMENT_NOTIFICATION_MIN_STREAK;
    }

    /** 미기록일은 SUCCESS로 간주해 성공 스트릭을 잇고 초과 스트릭을 끊는다. */
    private static int trailingStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate aggregationEndDate,
                                          DayStatus target) {
        Map<LocalDate, ChallengeDay> byDate = new HashMap<>();
        for (ChallengeDay d : days) {
            byDate.put(d.getDayDate(), d);
        }
        int streak = 0;
        for (LocalDate date = aggregationEndDate; !date.isBefore(startDate); date = date.minusDays(1)) {
            ChallengeDay d = byDate.get(date);
            DayStatus status = d == null ? DayStatus.SUCCESS : d.getStatus();
            if (status != target) {
                break;
            }
            streak++;
        }
        return streak;
    }

    /**
     * 종료 결과 status: 기간 총지출이 목표를 넘으면 FAIL, 이하면 SUCCESS(같으면 SUCCESS — 0727 PM 확정).
     * 일별 초과(OVER)는 달력 표시·overDays 집계로만 남고 성패를 가르지 않는다.
     * actualSpent는 summarizeThrough가 만든 값을 넘길 것 — 판정 근거와 응답의 총액이 같은 계산이어야 한다.
     */
    public static ChallengeStatus resultStatus(int actualSpent, int budgetTotal) {
        return actualSpent > budgetTotal ? ChallengeStatus.FAIL : ChallengeStatus.SUCCESS;
    }

}
