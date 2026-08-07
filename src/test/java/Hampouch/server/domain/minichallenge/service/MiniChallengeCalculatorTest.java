package Hampouch.server.domain.minichallenge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미니 집계 산식 검증 (그날 조회 응답의 progressDays·itemStreak·streakDays). 순수 로직 — Spring·DB 불필요.
 */
class MiniChallengeCalculatorTest {

    /** 조회 기준일 — 테스트 전반에서 "그날"로 쓰는 날짜. */
    private static final LocalDate D = LocalDate.of(2026, 7, 10);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 7, 31);

    @Test
    @DisplayName("progressDays는 시작 첫날 조회가 1이고, 조회일이 하루 지날 때마다 1씩 늘어난다")
    void progressDays_startsAtOne() {
        assertThat(MiniChallengeCalculator.progressDays(MONTH_START, MONTH_START)).isEqualTo(1);
        assertThat(MiniChallengeCalculator.progressDays(MONTH_START, MONTH_START.plusDays(1))).isEqualTo(2);
        assertThat(MiniChallengeCalculator.progressDays(MONTH_START, MONTH_START.plusDays(6))).isEqualTo(7);
    }

    @Test
    @DisplayName("itemStreak은 조회일부터 뒤로 연속 체크를 세고, 조회일을 체크했으면 그날도 포함된다")
    void itemStreak_includesCheckedQueryDay() {
        MiniCheckHistory h = history(MONTH_START, MONTH_END,
                D.minusDays(2), D.minusDays(1), D);
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isEqualTo(3);
    }

    @Test
    @DisplayName("조회일을 아직 체크 안 했으면 그날은 세지 않되 끊지도 않는다 — 전날까지의 연속을 보여준다")
    void itemStreak_skipsUncheckedQueryDay() {
        MiniCheckHistory h = history(MONTH_START, MONTH_END,
                D.minusDays(2), D.minusDays(1)); // 조회일 D는 미체크
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isEqualTo(2);
    }

    @Test
    @DisplayName("체크 안 한 날이 중간에 끼면 itemStreak 연속이 거기서 끊긴다")
    void itemStreak_brokenByGap() {
        MiniCheckHistory h = history(MONTH_START, MONTH_END,
                D.minusDays(3), D.minusDays(1), D); // D-2가 빠짐
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isEqualTo(2); // D, D-1에서 멈춤
    }

    @Test
    @DisplayName("itemStreak은 시작일 아래로 내려가지 않아 progressDays를 넘을 수 없다")
    void itemStreak_boundedByStartDate() {
        LocalDate start = D.minusDays(1); // 어제 시작 → progressDays = 2
        MiniCheckHistory h = history(start, start.plusDays(6), start, D); // 이틀 다 체크
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isEqualTo(2);
        assertThat(MiniChallengeCalculator.progressDays(start, D)).isEqualTo(2);
    }

    @Test
    @DisplayName("시작 첫날 아직 체크 전이면 itemStreak은 0이다 (첫날 경계)")
    void itemStreak_zeroOnUncheckedFirstDay() {
        MiniCheckHistory h = history(D, D.plusDays(6)); // 오늘 시작, 체크 없음
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isZero();
    }

    @Test
    @DisplayName("유저 스트릭(streakDays)은 그날 활성 미니를 전부 체크한 날의 연속이다 — 하나라도 빠진 날을 만나면 끊긴다 (0707 산식)")
    void userStreak_requiresAllActiveChecked() {
        MiniCheckHistory a = history(MONTH_START, MONTH_END, D.minusDays(2), D.minusDays(1), D);
        MiniCheckHistory b = history(MONTH_START, MONTH_END, D.minusDays(1), D); // D-2는 b 미체크
        assertThat(MiniChallengeCalculator.userStreakDays(List.of(a, b), D)).isEqualTo(2);
    }

    @Test
    @DisplayName("조회일에 전부 체크가 아직 안 됐으면(예: 4개 중 3개) 그날은 건너뛰고 전날부터 센다 — 진행 중인 하루가 스트릭을 0으로 만들지 않는다")
    void userStreak_skipsIncompleteQueryDay() {
        MiniCheckHistory a = history(MONTH_START, MONTH_END,
                D.minusDays(3), D.minusDays(2), D.minusDays(1), D);
        MiniCheckHistory b = history(MONTH_START, MONTH_END,
                D.minusDays(3), D.minusDays(2), D.minusDays(1)); // 조회일 D는 b 미체크(부분 체크)
        assertThat(MiniChallengeCalculator.userStreakDays(List.of(a, b), D)).isEqualTo(3);
    }

    @Test
    @DisplayName("과거에 활성 미니가 하나도 없던 날이 끼면 유저 스트릭이 거기서 끊긴다 — 빈 날은 달성한 날이 아니다")
    void userStreak_brokenByPastZeroActiveDay() {
        // D-2~D는 b가 활성·전부 체크라 달성이지만, 그 전날 D-3은 활성 미니가 0개(a는 D-4에 끝남) → 끊김
        MiniCheckHistory a = history(D.minusDays(5), D.minusDays(4), D.minusDays(5), D.minusDays(4)); // D-5~D-4 활성·전부 체크
        MiniCheckHistory b = history(D.minusDays(2), D.plusDays(4), D.minusDays(2), D.minusDays(1), D); // D-2~ 활성·전부 체크
        // D-3은 활성 미니가 0개 → 달성 아님 → D-2에서 멈춤
        assertThat(MiniChallengeCalculator.userStreakDays(List.of(a, b), D)).isEqualTo(3);
    }

    @Test
    @DisplayName("조회일에 활성 미니가 0개면 그날은 건너뛰고 전날까지의 연속을 보여준다 — 마지막 미니가 어제 끝나 오늘 진행 중인 게 없어도 스트릭이 0으로 떨어지지 않는다")
    void userStreak_skipsZeroActiveQueryDay() {
        // 어제까지 7일짜리 미니를 전일 체크로 끝냄 → 오늘은 활성 0개
        // 스킵 규칙이 없다면 오늘(활성 0 = 달성 아님)이 연속을 끊어 0이 된다. 시안의 빈 상태 화면
        // ("진행중인 챌린지가 없어요" + 연속 달성 3일째)이 바로 이 상태라, 유지 쪽이 디자인과 일치한다.
        LocalDate start = D.minusDays(7);
        LocalDate end = D.minusDays(1);
        LocalDate[] allDays = new LocalDate[7];
        for (int i = 0; i < 7; i++) {
            allDays[i] = start.plusDays(i);
        }
        MiniCheckHistory a = history(start, end, allDays);
        assertThat(MiniChallengeCalculator.userStreakDays(List.of(a), D)).isEqualTo(7);
    }

    @Test
    @DisplayName("과거 날짜로 조회하면(as-of) 그 날짜까지의 연속만 센다 — 조회일 이후의 체크는 영향이 없다")
    void asOfPastDate_ignoresLaterChecks() {
        MiniCheckHistory h = history(MONTH_START, MONTH_END,
                D.minusDays(1), D, D.plusDays(1), D.plusDays(2)); // 이후 이틀 치 체크가 더 있음
        assertThat(MiniChallengeCalculator.itemStreak(h, D)).isEqualTo(2);          // D-1, D까지만
        assertThat(MiniChallengeCalculator.userStreakDays(List.of(h), D)).isEqualTo(2);
    }

    private static MiniCheckHistory history(LocalDate start, LocalDate end, LocalDate... checks) {
        return new MiniCheckHistory(start, end, Set.of(checks));
    }
}
