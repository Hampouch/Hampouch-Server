package Hampouch.server.domain.challenge.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

final class FixedDateChallengeCycle {

    /**
     * 부분 주기의 목표를 배분할 때 기준으로 삼는 한 달 길이.
     * 실제 주기 길이(28~31일)로 나누면 같은 목표라도 설정한 달에 따라 첫 주기 예산이 갈린다.
     */
    static final int STANDARD_CYCLE_DAYS = 30;

    private FixedDateChallengeCycle() {
    }

    /**
     * 그 날짜가 고정일 주기의 시작 경계인지 — 아니면 설정 당일부터 시작한 부분 주기다.
     * 부분 주기는 최초 설정(create)에서만 생긴다. 도래 후 입장은 언제나 containing이 잡아준
     * 경계에서 시작하므로 항상 온전한 주기다.
     */
    static boolean isCycleStart(LocalDate date, int fixedDay) {
        return date.equals(containing(date, fixedDay).startDate());
    }

    static Plan startingOn(LocalDate startDate, int fixedDay) {
        validate(startDate, fixedDay);

        LocalDate nextBoundary = boundaryIn(YearMonth.from(startDate), fixedDay);
        if (!nextBoundary.isAfter(startDate)) {
            nextBoundary = boundaryIn(YearMonth.from(startDate).plusMonths(1), fixedDay);
        }
        return plan(startDate, nextBoundary);
    }

    /** 늦게 열어도 오늘이 속한 날짜 고정 주기의 원래 시작일과 종료일을 반환한다. */
    static Plan containing(LocalDate date, int fixedDay) {
        validate(date, fixedDay);

        YearMonth currentMonth = YearMonth.from(date);
        LocalDate currentBoundary = boundaryIn(currentMonth, fixedDay);
        LocalDate cycleStart = currentBoundary.isAfter(date)
                ? boundaryIn(currentMonth.minusMonths(1), fixedDay)
                : currentBoundary;
        LocalDate nextBoundary = boundaryIn(YearMonth.from(cycleStart).plusMonths(1), fixedDay);
        return plan(cycleStart, nextBoundary);
    }

    private static void validate(LocalDate date, int fixedDay) {
        if (date == null) {
            throw new IllegalArgumentException("date는 필수입니다.");
        }
        if (fixedDay < 1 || fixedDay > 31) {
            throw new IllegalArgumentException("fixedDay는 1 이상 31 이하여야 합니다: " + fixedDay);
        }
    }

    private static Plan plan(LocalDate startDate, LocalDate nextBoundary) {
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
