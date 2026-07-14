package Hampouch.server.domain.challenge.service;

import java.time.LocalDate;

/**
 * 시작일~끝일(양 끝 포함) 날짜 구간 값 객체.
 * "요청한 달 ∩ 챌린지 기간" 같은 구간 계산을 max/min 삼항식 대신 개념 이름(intersect)으로 읽히게 한다.
 */
public record DateRange(LocalDate start, LocalDate end) {

    /** 해당 연·월의 한 달 전체 구간 (1일 ~ 말일. 말일은 달 길이·윤년을 알아서 처리). */
    public static DateRange ofMonth(int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        // withDayOfMonth(n) = "일" 자리만 n으로 바꾼 날짜 (n일 뒤로 가는 건 plusDays — 혼동 주의).
        // 1일에서 일 자리를 그 달의 일수(lengthOfMonth)로 바꾸면 = 말일
        return new DateRange(first, first.withDayOfMonth(first.lengthOfMonth()));
    }

    /**
     * 두 구간의 교집합. 겹치지 않으면 빈 구간(isEmpty)이 된다.
     *
     * 교집합의 날짜는 두 구간 "모두"에 속해야 하므로 조건이 빡빡한 쪽이 이긴다 —
     * 시작은 두 시작 중 늦은 쪽(그 전엔 한쪽에만 속함), 끝은 두 끝 중 이른 쪽(그 후엔 한쪽에만 속함).
     *
     *   A:      7/1 |--------------------| 7/31
     *   B:           7/10 |------| 7/23
     *   교집합:       7/10 |------| 7/23
     */
    public DateRange intersect(DateRange other) {
        return new DateRange(laterOf(start, other.start), earlierOf(end, other.end));
    }

    /** 빈 구간 여부 — 교집합이 없으면 시작이 끝을 넘어선 상태가 된다. */
    public boolean isEmpty() {
        return start.isAfter(end);
    }

    /** 둘 중 늦은 날짜. 교집합의 시작 = 두 시작 중 늦은 쪽. */
    private static LocalDate laterOf(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? b : a;
    }

    /** 둘 중 이른 날짜. 교집합의 끝 = 두 끝 중 이른 쪽. */
    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? b : a;
    }
}
