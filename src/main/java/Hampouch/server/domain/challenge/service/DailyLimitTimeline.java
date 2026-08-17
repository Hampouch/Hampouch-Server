package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeAdjustment;

import java.time.LocalDate;
import java.util.List;

/**
 * "그날의 하루 한도"를 돌려주는 표. 조정(#7)이 없으면 기간 내내 한 값이고, 있으면 조정일을 경계로 계단처럼 바뀐다.
 *
 * 미기록일을 0원으로 집계하거나 과거 지출을 뒤늦게 입력할 때 당시 한도를 복원한다.
 */
public final class DailyLimitTimeline {

    /** 첫 조정 전 한도 = 기간 시작 시점의 한도. */
    private final int baseLimit;

    /** effectiveDate 오름차순. 조정은 챌린지당 최대 2건이라 이분 탐색 없이 훑는다. */
    private final List<ChallengeAdjustment> ascending;

    private DailyLimitTimeline(int baseLimit, List<ChallengeAdjustment> ascending) {
        this.baseLimit = baseLimit;
        this.ascending = ascending;
    }

    /**
     * 기준점을 challenge.dailyLimit이 아니라 첫 조정의 previousDailyLimit에서 가져오는 게 핵심이다 —
     * challenge.dailyLimit은 조정될 때마다 덮어써져 이미 "가장 최근 한도"라, 그걸 기준으로 삼으면
     * 조정 이전 날짜까지 새 한도로 계산돼 소급이 일어난다.
     */
    public static DailyLimitTimeline of(Challenge challenge, List<ChallengeAdjustment> ascendingAdjustments) {
        int base = ascendingAdjustments.isEmpty()
                ? challenge.getDailyLimit()
                : ascendingAdjustments.getFirst().getPreviousDailyLimit();
        return new DailyLimitTimeline(base, ascendingAdjustments);
    }

    /** 그날 적용되던 한도 — 그 날짜 이하의 마지막 조정 결과, 없으면 기준 한도. */
    public int on(LocalDate date) {
        int limit = baseLimit;
        for (ChallengeAdjustment a : ascending) {
            if (a.getEffectiveDate().isAfter(date)) {
                break;
            }
            limit = a.getNewDailyLimit();
        }
        return limit;
    }
}
