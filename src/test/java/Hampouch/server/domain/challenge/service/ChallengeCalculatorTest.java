package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.AlertLevel;
import Hampouch.server.domain.challenge.dto.ConsumptionCharacter;
import Hampouch.server.domain.challenge.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정·집계 공식 검증 (테스트시나리오_본챌린지.md). 순수 로직 — Spring·DB 불필요.
 */
class ChallengeCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 5, 1);

    @Test
    @DisplayName("14일 내내 한도 이내로 쓰면 성공으로 확정된다 — 절약액 68,200원은 (한도−지출)의 합, 실지출 211,800원, 연속 달성 14일 (S1)")
    void s1_success() {
        int dailyLimit = ChallengeCalculator.dailyLimit(280000, 14); // 20000
        assertThat(dailyLimit).isEqualTo(20000);

        List<ChallengeDay> days = new ArrayList<>();
        Challenge ch = challenge(dailyLimit);
        for (int i = 0; i < 13; i++) {
            days.add(day(ch, START.plusDays(i), 15000, dailyLimit));
        }
        days.add(day(ch, START.plusDays(13), 16800, dailyLimit));

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                days, DailyLimitTimeline.of(ch, List.of()), START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(14);
        assertThat(s.overDays()).isZero();
        assertThat(s.savedAmount()).isEqualTo(68200);
        assertThat(s.overAmount()).isZero();
        assertThat(s.maxStreak()).isEqualTo(14);
        assertThat(s.actualSpent()).isEqualTo(211800);
        assertThat(ChallengeCalculator.resultStatus(s.actualSpent(), ch.getBudgetTotal())).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("Map 버전 summarizeThrough도 List 버전과 같은 결과를 낸다 — s1과 같은 시나리오를 날짜별 지출 맵으로 재현 (#101 확장)")
    void summarizeThrough_map_success() {
        int dailyLimit = ChallengeCalculator.dailyLimit(280000, 14); // 20000
        Challenge ch = challenge(dailyLimit);
        Map<LocalDate, Long> spentByDate = new HashMap<>();
        for (int i = 0; i < 13; i++) {
            spentByDate.put(START.plusDays(i), 15000L);
        }
        spentByDate.put(START.plusDays(13), 16800L);

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                spentByDate, DailyLimitTimeline.of(ch, List.of()), START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(14);
        assertThat(s.overDays()).isZero();
        assertThat(s.savedAmount()).isEqualTo(68200);
        assertThat(s.overAmount()).isZero();
        assertThat(s.maxStreak()).isEqualTo(14);
        assertThat(s.actualSpent()).isEqualTo(211800);
    }

    @Test
    @DisplayName("Map 버전은 맵에 없는 날짜와 명시적으로 0을 넣은 날짜를 똑같이 0원 성공으로 계산한다 (#101 확장)")
    void summarizeThrough_map_countsMissingAndExplicitZeroAsSuccess() {
        int dailyLimit = 10000;
        Challenge ch = challenge(dailyLimit);
        LocalDate end = START.plusDays(3); // 기간 4일(5/1~5/4)
        Map<LocalDate, Long> spentByDate = new HashMap<>();
        spentByDate.put(START, 8000L);                  // 5/1 성공(절약 2000)
        spentByDate.put(START.plusDays(2), 12000L);      // 5/3 초과(2000)
        spentByDate.put(START.plusDays(3), 0L);          // 5/4 명시적 0원 성공 · 5/2는 맵에 아예 없음(미기록)

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                spentByDate, DailyLimitTimeline.of(ch, List.of()), START, end);

        assertThat(s.successDays()).isEqualTo(3);
        assertThat(s.overDays()).isEqualTo(1);
        assertThat(s.savedAmount()).isEqualTo(2000 + 10000 + 10000);
        assertThat(s.overAmount()).isEqualTo(2000);
        assertThat(s.actualSpent()).isEqualTo(20000);
        assertThat(s.maxStreak()).isEqualTo(2);
    }

    @Test
    @DisplayName("Map 버전 currentStreakAsOf도 맵에 없는 날짜를 0원 성공으로 이어간다 (#101 확장)")
    void currentStreakAsOf_map_countsMissingDateAsZeroSpentSuccess() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        DailyLimitTimeline limits = DailyLimitTimeline.of(ch, List.of());
        Map<LocalDate, Long> spentByDate = Map.of(
                START.plusDays(0), 1000L,    // SUCCESS
                START.plusDays(1), 1000L,    // SUCCESS
                START.plusDays(2), 99999L,   // OVER (연속 끊김)
                START.plusDays(3), 1000L);   // SUCCESS
        // START.plusDays(4)는 맵에 없음 — 0원 성공으로 취급돼 스트릭에 포함

        assertThat(ChallengeCalculator.currentStreakAsOf(spentByDate, limits, START, START.plusDays(4))).isEqualTo(2);
    }

    @Test
    @DisplayName("Map 버전 currentStreakAsOf는 저장된 status 없이 조정 경계마다 새 한도로 그 자리에서 다시 판정한다 (#101 확장)")
    void currentStreakAsOf_map_recomputesAcrossAdjustmentBoundary() {
        Challenge ch = Challenge.builder()
                .userId(1L).durationDays(5).startDate(START)
                .budgetTotal(55000).dailyLimit(11000).build();
        LocalDate adjustedOn = START.plusDays(2); // 5/3부터 한도 11000, 이전엔 10000
        DailyLimitTimeline limits = DailyLimitTimeline.of(ch, List.of(
                adjustment(ch, 1, adjustedOn, 10000, 11000)));
        // 날짜마다 전부 명시해 "맵에 없는 날 = 0원 성공"이 스트릭에 섞여 들어오지 않게 한다.
        Map<LocalDate, Long> spentByDate = Map.of(
                START, 10500L,                       // 5/1 — 옛 한도(10000) 기준 OVER
                START.plusDays(1), 10500L,           // 5/2 — 옛 한도 기준 OVER
                adjustedOn, 10500L,                   // 5/3 — 새 한도(11000) 기준 SUCCESS
                adjustedOn.plusDays(1), 10500L);     // 5/4 — 새 한도 기준 SUCCESS

        // 옛 한도를 계속 썼다면(=날짜별로 다시 안 쟀다면) 5/1·5/2도 새 한도 11000 기준 SUCCESS가 돼 스트릭이 4가 됐을 것.
        // 실제로는 5/2에서 옛 한도로 OVER가 나와 끊기므로, 그 자리에서 날짜별 한도를 정확히 재계산한다는 증거가 된다.
        assertThat(ChallengeCalculator.currentStreakAsOf(spentByDate, limits, START, adjustedOn.plusDays(1)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("하루 한도를 넘긴 날이 5일 있어도 총지출 259,100원이 목표 280,000원 이하라 성공이다 — 일별 초과는 overDays·초과액 집계로만 남는다")
    void s2_totalWithinBudget_success() {
        int dailyLimit = 20000;
        List<ChallengeDay> days = new ArrayList<>();
        Challenge ch = challenge(dailyLimit);
        for (int i = 0; i < 9; i++) {
            days.add(day(ch, START.plusDays(i), 15000, dailyLimit)); // 성공 9일
        }
        int[] over = {25000, 25000, 25000, 25000, 24100};
        for (int i = 0; i < over.length; i++) {
            days.add(day(ch, START.plusDays(9 + i), over[i], dailyLimit)); // 초과 5일
        }

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                days, DailyLimitTimeline.of(ch, List.of()), START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(9);
        assertThat(s.overDays()).isEqualTo(5);
        assertThat(s.overAmount()).isEqualTo(24100);
        assertThat(s.maxStreak()).isEqualTo(9);
        assertThat(s.savedAmount()).isEqualTo(45000);   // 성공 9일 × (20,000−15,000) — 초과일은 0으로 클램프
        assertThat(s.actualSpent()).isEqualTo(259100);  // 판정을 가르는 값 — PM이 확답에 든 예시 숫자 그대로
        assertThat(ChallengeCalculator.resultStatus(s.actualSpent(), ch.getBudgetTotal())).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("총지출 320,000원이 목표 280,000원을 넘으면 실패다 — 성공한 날이 10일로 더 많아도 성패는 총액만 본다")
    void totalOverBudget_fail() {
        int dailyLimit = 20000;
        List<ChallengeDay> days = new ArrayList<>();
        Challenge ch = challenge(dailyLimit);
        for (int i = 0; i < 10; i++) {
            days.add(day(ch, START.plusDays(i), 20000, dailyLimit)); // 성공 10일(정확히 한도)
        }
        for (int i = 0; i < 4; i++) {
            days.add(day(ch, START.plusDays(10 + i), 30000, dailyLimit)); // 초과 4일
        }

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                days, DailyLimitTimeline.of(ch, List.of()), START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(10);
        assertThat(s.overDays()).isEqualTo(4);
        assertThat(s.overAmount()).isEqualTo(40000);
        assertThat(s.savedAmount()).isZero();
        assertThat(s.maxStreak()).isEqualTo(10);
        assertThat(s.actualSpent()).isEqualTo(320000);
        assertThat(ChallengeCalculator.resultStatus(s.actualSpent(), ch.getBudgetTotal())).isEqualTo(ChallengeStatus.FAIL);
    }

    @Test
    @DisplayName("총지출이 목표와 정확히 같으면 성공이고, 1원이라도 넘으면 실패다")
    void resultStatus_boundary() {
        assertThat(ChallengeCalculator.resultStatus(280000, 280000)).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(ChallengeCalculator.resultStatus(280001, 280000)).isEqualTo(ChallengeStatus.FAIL);
    }

    @Test
    @DisplayName("지출이 한도와 정확히 같으면 성공이고, 1원이라도 넘으면 초과다 (S3 경계값)")
    void s3_boundary() {
        assertThat(ChallengeCalculator.judge(20000, 20000)).isEqualTo(DayStatus.SUCCESS);
        assertThat(ChallengeCalculator.judge(20001, 20000)).isEqualTo(DayStatus.OVER);
    }

    @Test
    @DisplayName("하루 한도는 버림으로 계산한다 — 100,000원 ÷ 30일 = 3,333원 (S4)")
    void s4_floor() {
        assertThat(ChallengeCalculator.dailyLimit(100000, 30)).isEqualTo(3333);
    }

    @Test
    @DisplayName("추천 목표는 성공이면 직전 목표의 90%, 금액 초과 실패면 직전 목표와 실지출의 중간값, 중도 포기·자동 취소면 직전 목표다")
    void recommendedBudgetTotal_dependsOnEndResult() {
        assertThat(ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.SUCCESS, null, 1, 0)).isZero();
        assertThat(ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.FAIL, null, 280000, 300000)).isEqualTo(290000);
        assertThat(ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.FAIL, null, 100, 101)).isEqualTo(101);
        assertThat(ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.FAIL, EndReason.GIVEN_UP, 300000, 4000)).isEqualTo(300000);
        assertThat(ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.VOID, EndReason.MISSING_DAILY_INPUT, 300000, 4000)).isEqualTo(300000);
    }

    @Test
    @DisplayName("성공 상태인데 실지출이 목표를 초과한 모순 데이터는 추천 목표를 계산하지 않는다")
    void recommendedBudgetTotal_rejectsInconsistentSuccess() {
        assertThatThrownBy(() -> ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.SUCCESS, null, 100, 101))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("성공한 챌린지의 실지출이 목표 금액을 초과했습니다.");
    }

    @Test
    @DisplayName("금액 초과 실패 상태인데 실지출이 목표 이하인 모순 데이터는 추천 목표를 계산하지 않는다")
    void recommendedBudgetTotal_rejectsInconsistentBudgetFailure() {
        assertThatThrownBy(() -> ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.FAIL, null, 100, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("금액 초과로 실패한 챌린지의 실지출이 목표 금액 이하입니다.");
        assertThatThrownBy(() -> ChallengeCalculator.recommendedBudgetTotal(
                ChallengeStatus.FAIL, null, 100, 99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("금액 초과로 실패한 챌린지의 실지출이 목표 금액 이하입니다.");
    }

    @Test
    @DisplayName("연속 성공은 집계 종료일부터 거꾸로 세며 미기록일도 0원 성공으로 이어진다")
    void currentStreakAsOf_countsUnrecordedDayAsZeroSpentSuccess() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(0), 1000, limit),  // SUCCESS
                day(ch, START.plusDays(1), 1000, limit),  // SUCCESS
                day(ch, START.plusDays(2), 99999, limit), // OVER (연속 끊김)
                day(ch, START.plusDays(3), 1000, limit)   // SUCCESS
        );
        assertThat(ChallengeCalculator.currentStreakAsOf(days, START, START.plusDays(4))).isEqualTo(2);
    }

    @Test
    @DisplayName("연속 초과는 미기록일을 0원 성공으로 계산하므로 그날 끊긴다")
    void trailingOverStreakAsOf_brokenByUnrecordedDay() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(1), 99999, limit),  // OVER
                day(ch, START.plusDays(2), 99999, limit),  // OVER
                day(ch, START.plusDays(3), 99999, limit)   // OVER → 3연속 (5/2~5/4)
        );
        assertThat(ChallengeCalculator.trailingOverStreakAsOf(days, START, START.plusDays(3))).isEqualTo(3); // 구간 끝 = 마지막 초과일
        assertThat(ChallengeCalculator.trailingOverStreakAsOf(days, START, START.plusDays(4))).isZero();
    }

    @Test
    @DisplayName("목표 조정 알림은 2일 연속 초과까지는 보내지 않고, 3일 연속부터 보낸다")
    void goalAdjustmentNotificationCondition_boundary() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(1), 99999, limit),  // OVER
                day(ch, START.plusDays(2), 99999, limit),  // OVER
                day(ch, START.plusDays(3), 99999, limit)   // OVER → 3연속
        );
        assertThat(ChallengeCalculator.meetsGoalAdjustmentNotificationCondition(days, START, START.plusDays(2))).isFalse();
        assertThat(ChallengeCalculator.meetsGoalAdjustmentNotificationCondition(days, START, START.plusDays(3))).isTrue();
    }

    @Test
    @DisplayName("사용률은 지출÷한도이고, 한도가 0이면 지출이 있을 때 1.0·없을 때 0.0으로 방어한다")
    void usageRate() {
        assertThat(ChallengeCalculator.usageRate(15000, 20000)).isEqualTo(0.75);
        assertThat(ChallengeCalculator.usageRate(0, 20000)).isEqualTo(0.0);
        assertThat(ChallengeCalculator.usageRate(100, 0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("캐릭터와 경고 레벨의 경계는 둘 다 사용률 30%·70%다 (0714 통일 — 응답 필드는 분리 유지)")
    void consumptionBoundaries() {
        // 캐릭터 경계 <30 / <70 / ≥70
        assertThat(ConsumptionCharacter.of(0.29)).isEqualTo(ConsumptionCharacter.FULL);
        assertThat(ConsumptionCharacter.of(0.30)).isEqualTo(ConsumptionCharacter.NORMAL);
        assertThat(ConsumptionCharacter.of(0.69)).isEqualTo(ConsumptionCharacter.NORMAL);
        assertThat(ConsumptionCharacter.of(0.70)).isEqualTo(ConsumptionCharacter.SKINNY);
        assertThat(ConsumptionCharacter.of(1.20)).isEqualTo(ConsumptionCharacter.SKINNY); // 초과
        // 알림 경계도 <30 / <70 / ≥70 — 0714 캐릭터와 통일(구 40/70)
        assertThat(AlertLevel.of(0.29)).isEqualTo(AlertLevel.NONE);
        assertThat(AlertLevel.of(0.30)).isEqualTo(AlertLevel.CAUTION);
        assertThat(AlertLevel.of(0.69)).isEqualTo(AlertLevel.CAUTION);
        assertThat(AlertLevel.of(0.70)).isEqualTo(AlertLevel.DANGER);
        // 같은 사용률이면 두 축이 같은 단계로 움직임(1:1) — 단 필드·enum은 역할이 달라 분리 유지
        assertThat(ConsumptionCharacter.of(0.35)).isEqualTo(ConsumptionCharacter.NORMAL);
        assertThat(AlertLevel.of(0.35)).isEqualTo(AlertLevel.CAUTION);
    }

    @Test
    @DisplayName("미기록일과 명시적으로 기록된 0원 모두 금액 집계에서는 0원 성공으로 계산한다")
    void summarizeThrough_countsUnrecordedAndRecordedZeroAsSuccess() {
        int dailyLimit = 10000;
        Challenge ch = challenge(dailyLimit);
        LocalDate end = START.plusDays(3); // 기간 4일 (5/1~5/4), 기록은 3일
        List<ChallengeDay> days = List.of(
                day(ch, START, 8000, dailyLimit),               // 5/1 성공(절약 2000)
                day(ch, START.plusDays(2), 12000, dailyLimit),  // 5/3 초과(2000)
                day(ch, START.plusDays(3), 0, dailyLimit));     // 5/4 명시적 0원 성공 · 5/2는 미기록

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                days, DailyLimitTimeline.of(ch, List.of()), START, end);

        assertThat(s.successDays()).isEqualTo(3);
        assertThat(s.overDays()).isEqualTo(1);
        assertThat(s.savedAmount()).isEqualTo(2000 + 10000 + 10000);
        assertThat(s.overAmount()).isEqualTo(2000);
        assertThat(s.actualSpent()).isEqualTo(20000);
        assertThat(s.maxStreak()).isEqualTo(2);
    }

    @Test
    @DisplayName("조정 가능 횟수는 기간 14일까지는 1회, 15일부터 2회다")
    void maxAdjustmentCount_splitsAtFourteenDays() {
        assertThat(ChallengeCalculator.maxAdjustmentCount(1)).isEqualTo(1);
        assertThat(ChallengeCalculator.maxAdjustmentCount(14)).isEqualTo(1);
        assertThat(ChallengeCalculator.maxAdjustmentCount(15)).isEqualTo(2);
        assertThat(ChallengeCalculator.maxAdjustmentCount(30)).isEqualTo(2);
    }

    @Test
    @DisplayName("조정 옵션은 현재 목표 금액에 배율을 곱한 뒤 버린다 — 나누어떨어지지 않는 금액이 올림으로 새지 않는다")
    void adjustOption_multipliesAndFloors() {
        assertThat(AdjustOption.PLUS_10.apply(20000)).isEqualTo(22000);
        assertThat(AdjustOption.PLUS_20.apply(20000)).isEqualTo(24000);
        assertThat(AdjustOption.PLUS_30.apply(20000)).isEqualTo(26000);
        assertThat(AdjustOption.PLUS_10.apply(3333)).isEqualTo(3666); // 3666.3 → 버림
        assertThat(AdjustOption.PLUS_20.apply(3333)).isEqualTo(3999); // 3999.6 → 버림
        assertThat(AdjustOption.PLUS_30.apply(3333)).isEqualTo(4332); // 4332.9 → 버림
        assertThat(AdjustOption.PLUS_10.apply(0)).isZero();
    }

    @Test
    @DisplayName("조정으로 도달할 수 있는 목표 금액의 최대치는 요청 상한에 1.3배를 두 번 적용한 16,900,000원이다")
    void adjustOption_reachableMaximumFromRequestCeiling() {
        // 요청 상한을 올리면 이 값이 함께 커지고, AdjustOption.apply가 int로 되돌릴 때 잘리지 않는다는 근거가 무너진다
        int first = AdjustOption.PLUS_30.apply(Challenge.BUDGET_TOTAL_MAX);
        int second = AdjustOption.PLUS_30.apply(first);

        assertThat(first).isEqualTo(13_000_000);
        assertThat(second).isEqualTo(16_900_000);
    }

    @Test
    @DisplayName("기간 도중 한도를 올려도 기록된 조정 전 날짜는 행에 저장된 옛 한도로 집계된다")
    void summarizeThrough_keepsPreAdjustmentDaysOnOldLimit() {
        Challenge ch = Challenge.builder()
                .userId(1L).durationDays(5).startDate(START)
                .budgetTotal(50000).dailyLimit(11000).build(); // 조정을 거친 뒤라 지금 한도는 11000
        LocalDate adjustedOn = START.plusDays(2); // 5/3부터 새 한도
        DailyLimitTimeline limits = DailyLimitTimeline.of(ch, List.of(
                adjustment(ch, 1, adjustedOn, 10000, 11000)));
        List<ChallengeDay> days = List.of(
                day(ch, START, 8000, 10000),          // 5/1 옛 한도로 판정된 기록 — 절약 2000
                day(ch, adjustedOn, 12000, 11000));   // 5/3 새 한도로 판정된 기록 — 초과 1000

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(days, limits, START, START.plusDays(4));

        assertThat(s.savedAmount()).isEqualTo(2000 + 10000 + 11000 + 11000);
        assertThat(s.overAmount()).isEqualTo(1000); // 새 한도 기준 — 옛 한도였다면 2000이 된다
        assertThat(s.successDays()).isEqualTo(4);
        assertThat(s.overDays()).isEqualTo(1);
        assertThat(s.actualSpent()).isEqualTo(20000);
    }

    @Test
    @DisplayName("새 한도는 조정한 날 당일부터 적용되고 그 전날은 옛 한도다")
    void timeline_switchesOnTheEffectiveDateItself() {
        Challenge ch = Challenge.builder()
                .userId(1L).durationDays(5).startDate(START)
                .budgetTotal(50000).dailyLimit(11000).build();
        LocalDate adjustedOn = START.plusDays(2);
        DailyLimitTimeline limits = DailyLimitTimeline.of(ch, List.of(
                adjustment(ch, 1, adjustedOn, 10000, 11000)));

        assertThat(limits.on(adjustedOn.minusDays(1))).isEqualTo(10000);
        assertThat(limits.on(adjustedOn)).isEqualTo(11000);
        assertThat(limits.on(adjustedOn.plusDays(1))).isEqualTo(11000);
    }

    @Test
    @DisplayName("조정이 두 번이면 각 조정일을 경계로 세 구간이 각자 한도를 갖는다")
    void timeline_handlesTwoAdjustments() {
        Challenge ch = Challenge.builder()
                .userId(1L).durationDays(30).startDate(START)
                .budgetTotal(300000).dailyLimit(12100).build();
        DailyLimitTimeline limits = DailyLimitTimeline.of(ch, List.of(
                adjustment(ch, 1, START.plusDays(5), 10000, 11000),
                adjustment(ch, 2, START.plusDays(10), 11000, 12100)));

        assertThat(limits.on(START)).isEqualTo(10000);
        assertThat(limits.on(START.plusDays(5))).isEqualTo(11000);
        assertThat(limits.on(START.plusDays(9))).isEqualTo(11000);
        assertThat(limits.on(START.plusDays(10))).isEqualTo(12100);
        assertThat(limits.on(START.plusDays(29))).isEqualTo(12100);
    }

    private static Challenge challenge(int dailyLimit) {
        return Challenge.builder()
                .userId(1L).durationDays(14).startDate(START)
                .budgetTotal(dailyLimit * 14).dailyLimit(dailyLimit).build();
    }

    /** 조정 이력 한 건. 목표 금액은 한도 × 기간으로 되돌려 채운다 — 타임라인은 한도만 보므로 값의 자릿수만 맞으면 된다. */
    private static ChallengeAdjustment adjustment(Challenge ch, int seq, LocalDate effectiveDate,
                                                  int previousDailyLimit, int newDailyLimit) {
        return ChallengeAdjustment.builder()
                .challenge(ch).sequenceNumber(seq).effectiveDate(effectiveDate)
                .option(AdjustOption.PLUS_10)
                .previousBudgetTotal(previousDailyLimit * ch.getDurationDays())
                .newBudgetTotal(newDailyLimit * ch.getDurationDays())
                .previousDailyLimit(previousDailyLimit)
                .newDailyLimit(newDailyLimit)
                .build();
    }

    private static ChallengeDay day(Challenge ch, LocalDate date, int spent, int dailyLimit) {
        return ChallengeDay.of(ch, date, spent, ChallengeCalculator.judge(spent, dailyLimit), dailyLimit);
    }
}
