package Hampouch.server.domain.challenge.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

final class FixedDateChallengeCycle {

    private FixedDateChallengeCycle() {
    }

    static Plan startingOn(LocalDate startDate, int fixedDay) {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate는 필수입니다.");
        }
        if (fixedDay < 1 || fixedDay > 31) {
            throw new IllegalArgumentException("fixedDay는 1 이상 31 이하여야 합니다: " + fixedDay);
        }

        LocalDate nextBoundary = boundaryIn(YearMonth.from(startDate), fixedDay);
        if (!nextBoundary.isAfter(startDate)) {
            nextBoundary = boundaryIn(YearMonth.from(startDate).plusMonths(1), fixedDay);
        }
        int durationDays = Math.toIntExact(ChronoUnit.DAYS.between(startDate, nextBoundary));
        return new Plan(startDate, nextBoundary.minusDays(1), durationDays);
    }

    private static LocalDate boundaryIn(YearMonth month, int fixedDay) {
        // 29~31일이 없는 달에도 주기를 끊을 수 있도록 그 달의 마지막 날을 경계로 사용한다.
        return month.atDay(Math.min(fixedDay, month.lengthOfMonth()));
    }

    record Plan(LocalDate startDate, LocalDate endDate, int durationDays) {
    }
}
