package Hampouch.server.domain.minichallenge.service;

import java.time.LocalDate;
import java.util.Set;

/**
 * 스트릭 계산 입력 — 미니 1건의 기간과 (조회일 이하로 잘라 온) 체크된 날짜 집합. 미니마다 하나씩 만든다.
 * 엔티티·DB id에 기대지 않는 순수 값이라 MiniChallengeCalculator를 저장 없이 단위 테스트할 수 있다
 * (#1의 ChallengeSummary·DateRange처럼 서비스 패키지의 계산 전용 부품).
 *
 * 체크된 날짜만으로는 계산이 안 돼서 기간을 같이 들고 있다 — startDate는 itemStreak이 뒤로 세다 멈출 하한이고,
 * 기간 전체는 userStreakDays가 "그날 활성인 미니"를 가려내는 isActiveOn의 근거다.
 * 반대로 계산에 안 쓰는 title·userId·checkedAt은 담지 않는다.
 */
public record MiniCheckHistory(
        LocalDate startDate,
        LocalDate endDate,
        Set<LocalDate> checkDates
) {

    /** 그 날짜에 활성(기간에 걸쳐 있는)인지 — MiniChallenge.isActiveOn과 같은 규칙. */
    public boolean isActiveOn(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public boolean isCheckedOn(LocalDate date) {
        return checkDates.contains(date);
    }
}
