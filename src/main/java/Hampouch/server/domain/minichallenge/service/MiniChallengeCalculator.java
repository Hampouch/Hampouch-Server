package Hampouch.server.domain.minichallenge.service;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 미니 챌린지 집계 산식의 단일 출처 (순수 함수, DB·Spring 의존 없음 → 단위 테스트 용이).
 * 집계는 저장 없이 조회 시 계산(ERD 확정) — 모든 계산이 조회 date 기준 as-of.
 *
 * progressDays = 시작일~조회 date 경과 일수(1부터)
 * itemStreak   = 그 항목을 연속 체크한 일수(조회 date부터 뒤로, 항상 ≤ progressDays)
 *                ★ 최장 연속이 아니라 "지금 며칠째"인 현재 연속이다. 그래서 끊기는 순간 멈추고, 그 앞에 더 긴
 *                연속이 있어도 보지 않는다(이미 끝난 과거라 "지금 며칠째"의 답이 아니다). 최장을 원하는 지표는
 *                본챌 결과 화면의 maxStreak이고, ChallengeCalculator는 그래서 앞으로 훑으며 Math.max를 쓴다.
 *                미니는 홈(진행 중)만 있어 현재 연속만 필요 — 본챌 홈의 currentStreakAsOf와 같은 계열이다.
 * streakDays   = 그날 활성 미니를 전부 체크한 날(활성이 1개 이상이면서 체크 수 = 활성 수)의 연속(유저 단위)
 *                itemStreak과는 독립된 지표라 둘 다 필요하다 — 이쪽은 itemStreak들로 계산해 낼 수 없다.
 *                min(itemStreak)도 아니다: 미니마다 시작일이 달라 "전부"의 기준이 날마다 바뀌기 때문이다
 *                (A만 있던 날 B를 요구하면 안 된다). 게다가 입력부터 다르다 — 응답 items는 조회일 활성만
 *                담지만 이쪽은 지금은 끝난 미니까지 전체 이력을 받는다. 과거 날짜엔 그 미니가 활성이었으니까.
 *
 * ★ 조회일 스킵 규칙(자체 결정): 조회 date 당일이 아직 조건 미충족이면(미체크/부분 체크)
 * 그날은 세지 않되 끊지도 않고 전날부터 거꾸로 센다. 근거 — 명세 §1 예시 JSON이
 * checkedCount 3/4(당일 미완료)인데 streakDays 3을 보여줌 = 진행 중인 하루가 스트릭을 0으로
 * 만들지 않는다는 뜻(#1 홈 집계의 "오늘은 기록 보냈을 때만 포함" 규칙과 같은 사상).
 * 조회일 이전 날짜는 미충족이면 그대로 끊는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE) // 정적 유틸의 인스턴스화 방지 자물쇠(#1 ChallengeCalculator와 동일)
public final class MiniChallengeCalculator {

    /**
     * 경과 일수 — 시작일 당일 = 1 (명세 §1: progressDays는 1부터). 호출부는 활성 미니(start ≤ date)만 넘긴다.
     * 체크 여부와 무관한 달력 경과다 — 하루도 체크 안 해도 날짜는 간다. 체크를 세는 건 itemStreak 쪽이고,
     * 그래서 항상 itemStreak ≤ progressDays다(체크한 날이 지나간 날보다 많을 수는 없다).
     */
    public static int progressDays(LocalDate startDate, LocalDate date) {
        return (int) (ChronoUnit.DAYS.between(startDate, date) + 1);
    }

    /**
     * 항목 스트릭 — 조회 date부터 거꾸로 연속 체크된 일수.
     * 조회일 미체크면 스킵 규칙(클래스 주석)대로 전날부터 세고, 시작일 밑으로는 내려가지 않아
     * 자연히 progressDays를 넘을 수 없다(명세 §1: itemStreak ≤ progressDays).
     */
    public static int itemStreak(MiniCheckHistory history, LocalDate date) {
        // cursor = 지금 보고 있는 날짜. 조회일에서 출발해 하루씩 뒤로 옮기며 훑는 손가락이다.
        // 출발점이 조건부인 건 스킵 규칙 때문 — 조회일이 미체크면 그날은 건너뛰고 전날에 놓고 시작한다.
        LocalDate cursor = history.isCheckedOn(date) ? date : date.minusDays(1);
        int streak = 0;
        while (!cursor.isBefore(history.startDate()) && history.isCheckedOn(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /**
     * 유저 스트릭(streakDays) — "그날 활성 미니를 전부 체크한 날"의 연속을 조회 date부터 거꾸로 센다(0707 확정 산식).
     * 활성 미니가 0개인 날은 달성한 날이 아니므로(산식의 "활성 1개 이상" 조건) 연속을 끊는다.
     * 단 조회일 당일만은 스킵 규칙(클래스 주석) 적용 — 부분 체크든 활성 0개든 그날은 건너뛰고 전날부터 센다.
     * 종료 보장: 거꾸로 가다 보면 가장 이른 시작일 이전은 활성 0개라 반드시 멈춘다.
     */
    public static int userStreakDays(List<MiniCheckHistory> histories, LocalDate date) {
        LocalDate cursor = isDayAllChecked(histories, date) ? date : date.minusDays(1);
        int streak = 0;
        while (isDayAllChecked(histories, cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** 그날 활성 미니가 1개 이상이고 전부 체크됐는지 (checkedCount = totalCount > 0). */
    private static boolean isDayAllChecked(List<MiniCheckHistory> histories, LocalDate day) {
        int activeCount = 0;
        for (MiniCheckHistory h : histories) {
            if (!h.isActiveOn(day)) {
                continue;
            }
            activeCount++;
            if (!h.isCheckedOn(day)) {
                return false;
            }
        }
        return activeCount > 0;
    }
}
