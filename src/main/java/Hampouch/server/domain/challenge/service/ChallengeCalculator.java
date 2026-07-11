package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Comparator;
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
 * 결과 status = OVER 1일+ 이면 FAIL, 아니면 SUCCESS
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE) // 엔티티의 PROTECTED와 반대 용도 — 아무도 호출하지 않는 자물쇠. 자동 public 생성자를 차단해 정적 유틸의 인스턴스화 방지
public final class ChallengeCalculator {

    /** 하루 한도 = 목표 ÷ 기간 (정수 나눗셈 = 버림). */
    public static int dailyLimit(int budgetTotal, int durationDays) {
        return budgetTotal / durationDays;
    }

    /** 일별 판정. */
    public static DayStatus judge(int spentAmount, int dailyLimit) {
        return spentAmount <= dailyLimit ? DayStatus.SUCCESS : DayStatus.OVER;
    }

    /** 기록된 일자들로 집계 요약 계산. */
    public static ChallengeSummary summarize(List<ChallengeDay> days, int dailyLimit) {
        int successDays = 0;
        int overDays = 0;
        int savedAmount = 0;
        int overAmount = 0;
        int actualSpent = 0;
        int maxStreak = 0;
        int currentStreak = 0;

        for (ChallengeDay d : orderedByDate(days)) {
            int spent = d.getSpentAmount();
            actualSpent += spent;
            savedAmount += Math.max(0, dailyLimit - spent);
            overAmount += Math.max(0, spent - dailyLimit);
            if (d.getStatus() == DayStatus.SUCCESS) {
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

    /**
     * 결과 확정용 집계 — 미입력일(행 없는 날)은 0원 지출 = SUCCESS로 간주(0630 확정, 명세 §4).
     * successDays에 포함하고 절약액엔 그날 한도 전액을 가산하며, streak도 미입력일을 건너뛰지 않고 이어 센다.
     * 기간(startDate~endDate)을 달력 순서로 직접 순회하므로 별도 정렬이 필요 없다.
     */
    public static ChallengeSummary summarizeForResult(List<ChallengeDay> days, int dailyLimit,
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

    /** 진행 중 연속 성공 — 마지막 기록일부터 거꾸로 센 연속 성공일 수. */
    public static int currentStreak(List<ChallengeDay> days) {
        List<ChallengeDay> ordered = orderedByDate(days);
        int streak = 0;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (ordered.get(i).getStatus() == DayStatus.SUCCESS) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** 하루 사용률 = 지출 ÷ 한도. 한도 0 방어(지출 있으면 1.0, 없으면 0.0). */
    public static double usageRate(int todaySpent, int dailyLimit) {
        if (dailyLimit <= 0) {
            return todaySpent > 0 ? 1.0 : 0.0;
        }
        return (double) todaySpent / dailyLimit;
    }

    /** 마지막 기록일부터 거꾸로 센 연속 초과(OVER)일 수 — 경고 카드(GOAL_TOO_TIGHT)용. */
    public static int trailingOverStreak(List<ChallengeDay> days) {
        List<ChallengeDay> ordered = orderedByDate(days);
        int streak = 0;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (ordered.get(i).getStatus() == DayStatus.OVER) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** 종료 결과 status: 초과한 날 1일 이상이면 FAIL, 전부 성공이면 SUCCESS. */
    public static ChallengeStatus resultStatus(List<ChallengeDay> days) {
        boolean anyOver = days.stream().anyMatch(d -> d.getStatus() == DayStatus.OVER);
        return anyOver ? ChallengeStatus.FAIL : ChallengeStatus.SUCCESS;
    }

    /** 날짜(dayDate) 오름차순 정렬 사본 — 연속(streak) 계산은 날짜순이 전제라 DB 반환 순서를 믿지 않고 보장. */
    private static List<ChallengeDay> orderedByDate(List<ChallengeDay> days) {
        return days.stream()
                .sorted(Comparator.comparing(ChallengeDay::getDayDate))
                .toList();
    }
}
