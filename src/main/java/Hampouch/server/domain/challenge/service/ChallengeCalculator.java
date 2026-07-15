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
 * 결과 status = OVER 1일+ 이면 FAIL, 아니면 SUCCESS
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

    /**
     * 홈(진행 중) 연속 성공 — 판정 완료 구간의 끝(lastJudgedDate)부터 거꾸로 센 연속 성공일 수.
     * 미기록일은 0원 지출 = SUCCESS로 채워 센다(0714 확정 — 홈도 결과와 동일 규칙).
     */
    public static int currentStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingStreakAsOf(days, startDate, lastJudgedDate, DayStatus.SUCCESS);
    }

    /** 하루 사용률 = 지출 ÷ 한도. 한도 0 방어(지출 있으면 1.0, 없으면 0.0). */
    public static double usageRate(int todaySpent, int dailyLimit) {
        if (dailyLimit <= 0) {
            return todaySpent > 0 ? 1.0 : 0.0;
        }
        return (double) todaySpent / dailyLimit;
    }

    /**
     * 판정 완료 구간의 끝부터 거꾸로 센 연속 초과(OVER)일 수 — 경고 카드(GOAL_TOO_TIGHT)용.
     * 미기록일 = SUCCESS로 채우므로, 하루라도 건너뛰면 연속이 끊긴다(0714 확정 — 미기록 경과도 회복).
     */
    public static int trailingOverStreakAsOf(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingStreakAsOf(days, startDate, lastJudgedDate, DayStatus.OVER);
    }

    /** GOAL_TOO_TIGHT 발동 기준 — 마지막 3일 연속 한도 초과(0707 확정). 기준이 바뀌면 여기 한 곳만 고친다. */
    private static final int GOAL_TOO_TIGHT_MIN_STREAK = 3;

    /** 경고 카드 GOAL_TOO_TIGHT 발동 여부. 오늘 사용률(alertLevel)과 무관 — 카드는 게이트 없이 자기 트리거만 본다(0713 확정). */
    public static boolean isGoalTooTight(List<ChallengeDay> days, LocalDate startDate, LocalDate lastJudgedDate) {
        return trailingOverStreakAsOf(days, startDate, lastJudgedDate) >= GOAL_TOO_TIGHT_MIN_STREAK;
    }

    /**
     * 공용 스트릭 엔진 — lastJudgedDate부터 startDate까지 거꾸로 내려가며 "target으로 지정한 상태"가
     * 이어지는 일수를 센다(target 아닌 날을 만나면 끊김). 아무 상태나 세는 게 아니라 한 가지 상태의 연속.
     * 진입점: currentStreakAsOf가 SUCCESS를(연속 성공), trailingOverStreakAsOf가 OVER를(연속 초과) 주입.
     * 미기록일 = SUCCESS 간주 — 그래서 target=SUCCESS면 미기록일이 연속을 잇고, target=OVER면 끊는다.
     */
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

    /** 종료 결과 status: 초과한 날 1일 이상이면 FAIL, 전부 성공이면 SUCCESS. */
    public static ChallengeStatus resultStatus(List<ChallengeDay> days) {
        boolean anyOver = days.stream().anyMatch(d -> d.getStatus() == DayStatus.OVER);
        return anyOver ? ChallengeStatus.FAIL : ChallengeStatus.SUCCESS;
    }

}
