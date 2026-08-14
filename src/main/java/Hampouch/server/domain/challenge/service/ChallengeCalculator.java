package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 챌린지 판정·집계 규칙의 단일 출처 (순수 함수, DB·Spring 의존 없음 → 단위 테스트 용이).
 *
 * dailyLimit = floor(budgetTotal / durationDays)
 * 일별 판정  = spent ≤ dailyLimit ? SUCCESS : OVER
 * savedAmount = Σ max(0, dailyLimit − spent)
 * overAmount  = Σ max(0, spent − dailyLimit)
 * 결과 status = 기간 총지출(actualSpent) ≤ budgetTotal 이면 SUCCESS, 넘으면 FAIL (0727 PM 확정 — 일별 OVER는 성패와 무관)
 * GOAL_TOO_TIGHT = 판정 완료 구간 마지막 3일 연속 초과
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE) // 엔티티의 PROTECTED와 반대 용도 — 아무도 호출하지 않는 자물쇠. 자동 public 생성자를 차단해 정적 유틸의 인스턴스화 방지
public final class ChallengeCalculator {

    /** 하루 한도 = 목표 ÷ 기간 (정수 나눗셈 = 버림). */
    public static int dailyLimit(int budgetTotal, int durationDays) {
        return budgetTotal / durationDays;
    }

    /**
     * 일별 판정 = 하루 채점. "판정"은 이 도메인에서 그날 지출을 하루 한도와 비교해
     * 성공(SUCCESS)/초과(OVER)를 가리는 행위를 말한다 — 결과 화면 달력의 도장 색이 이 결과.
     */
    public static DayStatus judge(int spentAmount, int dailyLimit) {
        return spentAmount <= dailyLimit ? DayStatus.SUCCESS : DayStatus.OVER;
    }

    /**
     * 금액·진행도 집계에서는 기록이 없는 날의 지출액을 0원으로 계산한다.
     * 따라서 그날은 SUCCESS이고 하루 한도 전액이 절약액에 더해지며 성공 스트릭도 이어진다.
     * 입력 완료 여부는 별도 규칙이라, 연속 미입력은 ChallengeService에서 경고·무효 처리한다.
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

    /** 기간이 이 일수 이하면 조정 1회, 넘으면 2회 (0728 전체 6차 확정). 기준은 남은 기간이 아니라 전체 챌린지 기간 — 0801 최연우 답변. */
    private static final int SHORT_CHALLENGE_MAX_DAYS = 14;

    /** 조정 가능 횟수. 기간별로 갈리므로 화면이 아니라 서버가 값을 내려준다(안드도 조건을 안 들고 있음 — 0728 회의록). */
    public static int maxAdjustmentCount(int durationDays) {
        return durationDays <= SHORT_CHALLENGE_MAX_DAYS ? 1 : 2;
    }

    /** 집계 종료일부터 거꾸로 센 연속 성공일 수. 미기록일도 0원 SUCCESS로 계산한다. */
    public static int currentStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate aggregationEndDate) {
        return trailingStreakAsOf(days, startDate, aggregationEndDate, DayStatus.SUCCESS);
    }

    /**
     * 하루 사용률 = 지출 ÷ 한도. 아래 분기는 한도 0원(저예산·장기간 조합, 하한은 PM 질문 4)의 특별 규칙이 아니라
     * 0으로 나누기 구멍 메우기다 — double 나눗셈은 0으로 나눠도 예외 없이 Infinity(지출>0)나 NaN(0÷0)을 돌려주는데,
     * 그대로 두면 JSON 응답이 비표준 값으로 오염되고 NaN은 모든 비교가 false라 AlertLevel 분기가 엉뚱한 데로 떨어진다.
     * 메우는 값은 일반 구간과 같은 규칙("넘었나?" = judge의 경계)으로 직접 판정 — 지출 있음 = 한도 0원 초과 확정이라
     * 무한대 대신 1.0, 지출 없음 = 안 넘음(0 ≤ 0)이라 0.0.
     */
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

    /** GOAL_TOO_TIGHT 발동 기준 — 마지막 3일 연속 한도 초과(0707 확정). 기준이 바뀌면 여기 한 곳만 고친다. */
    private static final int GOAL_TOO_TIGHT_MIN_STREAK = 3;

    /** 경고 카드 GOAL_TOO_TIGHT 발동 여부. 오늘 사용률(alertLevel)과 무관 — 카드는 게이트 없이 자기 트리거만 본다(0713 확정). */
    public static boolean isGoalTooTight(List<ChallengeDay> days, LocalDate startDate, LocalDate aggregationEndDate) {
        return trailingOverStreakAsOf(days, startDate, aggregationEndDate) >= GOAL_TOO_TIGHT_MIN_STREAK;
    }

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
