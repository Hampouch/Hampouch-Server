package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.AlertLevel;
import Hampouch.server.domain.challenge.dto.ConsumptionCharacter;
import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정·집계 공식 검증 (테스트시나리오_본챌린지.md S1~S4). 순수 로직 — Spring·DB 불필요.
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

        ChallengeSummary s = ChallengeCalculator.summarizeForResult(days, dailyLimit, START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(14);
        assertThat(s.overDays()).isZero();
        assertThat(s.savedAmount()).isEqualTo(68200);
        assertThat(s.overAmount()).isZero();
        assertThat(s.maxStreak()).isEqualTo(14);
        assertThat(s.actualSpent()).isEqualTo(211800);
        assertThat(ChallengeCalculator.resultStatus(days)).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("초과한 날이 하루라도 있으면 실패로 확정된다 — 초과액 24,100원은 (지출−한도)의 합, 최장 연속 성공은 초과 전 9일 (S2)")
    void s2_fail() {
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

        ChallengeSummary s = ChallengeCalculator.summarizeForResult(days, dailyLimit, START, START.plusDays(13));
        assertThat(s.successDays()).isEqualTo(9);
        assertThat(s.overDays()).isEqualTo(5);
        assertThat(s.overAmount()).isEqualTo(24100);
        assertThat(s.maxStreak()).isEqualTo(9);
        assertThat(ChallengeCalculator.resultStatus(days)).isEqualTo(ChallengeStatus.FAIL);
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
    @DisplayName("연속 성공은 판정 완료 구간의 끝에서 거꾸로 세고, 기록 없는 날은 0원=성공으로 채워 이어진다 (0714)")
    void currentStreakAsOf_countsBackFillingUnrecorded() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(0), 1000, limit),  // SUCCESS
                day(ch, START.plusDays(1), 1000, limit),  // SUCCESS
                day(ch, START.plusDays(2), 99999, limit), // OVER (연속 끊김)
                day(ch, START.plusDays(3), 1000, limit)   // SUCCESS
        );
        // 구간 끝 5/5는 미기록 → 성공으로 채워져 이어짐: 5/5 + 5/4 = 2 (5/3 OVER에서 끊김)
        assertThat(ChallengeCalculator.currentStreakAsOf(days, START, START.plusDays(4))).isEqualTo(2);
    }

    @Test
    @DisplayName("연속 초과는 구간 끝에서 거꾸로 세고, 기록 없는 날(성공 취급)을 만나면 끊긴다 (0714)")
    void trailingOverStreakAsOf_brokenByUnrecordedDay() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(1), 99999, limit),  // OVER
                day(ch, START.plusDays(2), 99999, limit),  // OVER
                day(ch, START.plusDays(3), 99999, limit)   // OVER → 3연속 (5/2~5/4)
        );
        assertThat(ChallengeCalculator.trailingOverStreakAsOf(days, START, START.plusDays(3))).isEqualTo(3); // 구간 끝 = 마지막 초과일
        assertThat(ChallengeCalculator.trailingOverStreakAsOf(days, START, START.plusDays(4))).isZero();     // 다음날 미기록 = 성공 → 끊김
    }

    @Test
    @DisplayName("경고 카드는 2일 연속 초과까지는 안 뜨고, 3일 연속부터 발동한다 (0707 확정 경계)")
    void isGoalTooTight_boundary() {
        int limit = 20000;
        Challenge ch = challenge(limit);
        List<ChallengeDay> days = List.of(
                day(ch, START.plusDays(1), 99999, limit),  // OVER
                day(ch, START.plusDays(2), 99999, limit),  // OVER
                day(ch, START.plusDays(3), 99999, limit)   // OVER → 3연속
        );
        assertThat(ChallengeCalculator.isGoalTooTight(days, START, START.plusDays(2))).isFalse(); // 구간 끝이 2연속째 → 미발동
        assertThat(ChallengeCalculator.isGoalTooTight(days, START, START.plusDays(3))).isTrue();  // 3연속째 → 발동
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
    @DisplayName("기록 없는 날은 0원 지출=성공으로 집계된다 — 성공일에 포함되고, 한도 전액이 절약액에 더해지고, 연속도 이어진다 (0630 확정)")
    void summarizeForResult_fillsUnrecordedDaysAsSuccess() {
        int dailyLimit = 10000;
        Challenge ch = challenge(dailyLimit);
        LocalDate end = START.plusDays(3); // 기간 4일 (5/1~5/4), 기록은 2일뿐
        List<ChallengeDay> days = List.of(
                day(ch, START, 8000, dailyLimit),               // 5/1 성공(절약 2000)
                day(ch, START.plusDays(2), 12000, dailyLimit)); // 5/3 초과(2000) · 5/2, 5/4는 미입력

        ChallengeSummary s = ChallengeCalculator.summarizeForResult(days, dailyLimit, START, end);

        assertThat(s.successDays()).isEqualTo(3);              // 5/1 + 미입력 5/2·5/4
        assertThat(s.overDays()).isEqualTo(1);
        assertThat(s.savedAmount()).isEqualTo(2000 + 10000 + 10000); // 기록 성공일 2000 + 미입력 2일은 한도 전액
        assertThat(s.overAmount()).isEqualTo(2000);
        assertThat(s.actualSpent()).isEqualTo(20000);
        assertThat(s.maxStreak()).isEqualTo(2);                // 5/1~5/2 — 미입력일이 streak을 이어줌
    }

    private static Challenge challenge(int dailyLimit) {
        return Challenge.create(1L, 14, START, dailyLimit * 14, dailyLimit, false, null);
    }

    private static ChallengeDay day(Challenge ch, LocalDate date, int spent, int dailyLimit) {
        return ChallengeDay.of(ch, date, spent, ChallengeCalculator.judge(spent, dailyLimit));
    }
}
