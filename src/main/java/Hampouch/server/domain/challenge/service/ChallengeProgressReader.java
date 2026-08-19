package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * ChallengeProgressQuery 구현. 챌린지 한 건과 그 기간의 지출만 읽고 날짜·금액을 비교하는 자리라
 * ChallengeService와 분리해 둔다 — 여기가 그 서비스에 얹히면 Expense -> Challenge -> Expense로 의존이 돈다.
 * ExpenseService는 그 반대 방향(지출 집계)만 쓰므로 순환이 되지 않는다.
 * 조회가 단순 읽기라 별도 트랜잭션 경계를 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChallengeProgressReader implements ChallengeProgressQuery {

    private final ChallengeRepository challengeRepository;
    private final ExpenseService expenseService;
    private final Clock clock; // 배포 환경의 기본 시간대와 무관하게 한국 날짜를 사용한다.

    /**
     * 겹침 판정은 DateRange.intersect에 맡긴다. 직접 비교하면 !end.isBefore(start) 같은 부정문 두 개가 되는데,
     * 교집합이 비었는지 묻는 편이 "하루라도 겹치면 true"라는 규칙에 그대로 대응한다.
     * 페이스 확인은 겹칠 때만 한다 — 겹치지 않으면 어차피 문구가 안 바뀌므로 지출을 읽을 이유가 없다.
     */
    @Override
    public ChallengeProgress overlappingChallengeProgress(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        DateRange period = new DateRange(periodStart, periodEnd);
        return challengeRepository.findInProgress(userId)
                .filter(challenge -> !period.intersect(rangeOf(challenge)).isEmpty())
                .map(challenge -> isOnTrack(userId, challenge) ? ChallengeProgress.ON_TRACK : ChallengeProgress.OVER_PACE)
                .orElse(ChallengeProgress.NONE);
    }

    /**
     * 챌린지 시작일부터 오늘까지 실제로 쓴 돈이 경과일만큼의 예산 안에 있는지.
     * 조회 기간이 아니라 챌린지 기간을 보는 이유는, 사용자가 어느 달을 조회하든 "지금 챌린지를 잘 지키고 있는가"는
     * 하나로 정해져 있어야 하기 때문이다(5월을 봐도 6월을 봐도 같은 답이 나와야 한다).
     */
    private boolean isOnTrack(Long userId, Challenge challenge) {
        LocalDate today = LocalDate.now(clock);
        int elapsedDays = ChallengeCalculator.elapsedDays(challenge.getStartDate(), challenge.getEndDate(), today);
        if (elapsedDays == 0) {
            return true; // 아직 시작 전 - 쓴 돈이 없으니 지키고 있는 것으로 본다.
        }

        LocalDate through = challenge.getStartDate().plusDays(elapsedDays - 1L);
        long actualSpent = expenseService.getDailySpending(userId, challenge.getStartDate(), through)
                .values().stream()
                .mapToLong(Long::longValue)
                .sum();

        return ChallengeCalculator.isWithinBudgetPace(
                actualSpent, challenge.getBudgetTotal(), challenge.getDurationDays(), elapsedDays);
    }

    private static DateRange rangeOf(Challenge challenge) {
        return new DateRange(challenge.getStartDate(), challenge.getEndDate());
    }
}
