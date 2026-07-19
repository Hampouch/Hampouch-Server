package Hampouch.server.domain.minichallenge.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/mini-challenges?date= 응답 — 그날 나의 미니 챌린지(명세 §1).
 * date는 실제 계산 기준일(생략 시 오늘로 채워진 값)을 그대로 돌려준다 —
 * 응답만 봐도 어느 날 기준 집계인지 알 수 있게(#1 CalendarResponse의 요청값 echo와 같은 이유).
 * 집계는 전부 저장 없이 조회 시 계산(as-of).
 */
public record DailyMiniChallengesResponse(
        LocalDate date,
        Summary summary,
        List<Item> items
) {

    /** totalCount = 그날 활성 미니 수, checkedCount = 그중 체크된 수, streakDays = 전부 체크한 날의 연속(유저 단위). */
    public record Summary(
            int checkedCount,
            int totalCount,
            int streakDays
    ) {
    }

    /** items 원소 = 그날 활성인 미니 1건의 as-of 현황. 이 응답 전용 부품이라 중첩으로 소속을 명시. */
    public record Item(
            Long miniChallengeId,
            String title,
            int durationDays,
            int progressDays,
            int itemStreak,
            boolean checked
    ) {
    }
}
