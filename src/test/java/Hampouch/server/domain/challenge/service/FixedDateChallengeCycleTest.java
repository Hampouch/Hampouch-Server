package Hampouch.server.domain.challenge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FixedDateChallengeCycleTest {

    @Test
    @DisplayName("8월 7일에 매월 1일 고정을 선택하면 9월 1일을 다음 경계로 보고 8월 31일까지 25일 챌린지를 만든다")
    void firstCycleStartsImmediatelyAndEndsBeforeFixedBoundary() {
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 8, 7), 1);

        assertThat(plan.startDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(plan.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(plan.durationDays()).isEqualTo(25);
    }

    @Test
    @DisplayName("고정일 당일 시작은 같은 날에 끝내지 않고 다음 달 고정일 직전까지 한 주기를 만든다")
    void cycleOnFixedDayUsesNextMonthBoundary() {
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 6, 1), 1);

        assertThat(plan.endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(plan.durationDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("31일이 없는 달은 말일을 고정 경계로 보정해 주기가 끊기거나 겹치지 않는다")
    void fixedDayThirtyOneClampsToMonthEnd() {
        FixedDateChallengeCycle.Plan january = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 1, 31), 31);
        FixedDateChallengeCycle.Plan february = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 2, 28), 31);

        assertThat(january.endDate()).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(january.durationDays()).isEqualTo(28);
        assertThat(february.endDate()).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(february.durationDays()).isEqualTo(31);
    }
}
