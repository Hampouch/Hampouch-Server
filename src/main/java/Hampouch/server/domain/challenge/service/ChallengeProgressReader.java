package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * ChallengeProgressQuery 구현. 리포지토리 한 번만 읽고 날짜만 비교하는 자리라
 * ChallengeService와 분리해 둔다 — 여기가 그 서비스에 얹히면 Expense -> Challenge -> Expense로 의존이 돈다.
 * 조회가 단건이라 별도 트랜잭션 경계를 두지 않는다(리포지토리 호출 자체의 트랜잭션으로 충분하다).
 */
@Component
@RequiredArgsConstructor
public class ChallengeProgressReader implements ChallengeProgressQuery {

    private final ChallengeRepository challengeRepository;

    /**
     * 겹침 판정은 DateRange.intersect에 맡긴다. 직접 비교하면 !end.isBefore(start) 같은 부정문 두 개가 되는데,
     * 교집합이 비었는지 묻는 편이 "하루라도 겹치면 true"라는 규칙에 그대로 대응한다.
     */
    @Override
    public boolean hasInProgressChallengeOverlapping(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        DateRange period = new DateRange(periodStart, periodEnd);
        return challengeRepository.findInProgress(userId)
                .map(challenge -> !period.intersect(rangeOf(challenge)).isEmpty())
                .orElse(false);
    }

    private static DateRange rangeOf(Challenge challenge) {
        return new DateRange(challenge.getStartDate(), challenge.getEndDate());
    }
}
