package Hampouch.server.domain.challenge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FixedDateChallengeCycleTest {

    @Test
    @DisplayName("8월 7일에 매월 1일 고정을 설정하면 첫 챌린지는 8월 31일까지 25일간 진행된다")
    void firstCycleStartsImmediatelyAndEndsBeforeFixedBoundary() {
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 8, 7), 1);

        assertThat(plan.startDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(plan.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(plan.durationDays()).isEqualTo(25);
    }

    @Test
    @DisplayName("고정일에 시작하면 다음 달 고정일 전날에 종료된다")
    void cycleOnFixedDayUsesNextMonthBoundary() {
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 6, 1), 1);

        assertThat(plan.endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(plan.durationDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("2월의 29~31일 고정은 비윤년에는 28일, 윤년에는 29일을 다음 시작일로 사용한다")
    void fixedDaysAtEndOfFebruaryUseLastDay() {
        assertFebruaryStartDate(2026, LocalDate.of(2026, 2, 28));
        assertFebruaryStartDate(2028, LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("2월 말일에 시작한 매월 31일 고정 챌린지는 3월 30일에 종료된다")
    void fixedDayThirtyOneContinuesToMarchBoundary() {
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                LocalDate.of(2026, 2, 28), 31);

        assertThat(plan.endDate()).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(plan.durationDays()).isEqualTo(31);
    }

    private static void assertFebruaryStartDate(int year, LocalDate expectedStartDate) {
        for (int fixedDay = 29; fixedDay <= 31; fixedDay++) {
            FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.startingOn(
                    LocalDate.of(year, 1, fixedDay), fixedDay);

            assertThat(plan.endDate().plusDays(1))
                    .as("%d년 매월 %d일 고정", year, fixedDay)
                    .isEqualTo(expectedStartDate);
        }
    }
}
