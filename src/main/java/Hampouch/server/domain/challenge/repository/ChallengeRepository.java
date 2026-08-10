package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);

    default boolean existsInProgress(Long userId) {
        return existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    default Optional<Challenge> findInProgress(Long userId) {
        return findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 포기한 챌린지는 목표 endDate를 보존하므로 직전 종료 건은 createdAt으로 고른다.
     * id는 createdAt이 같은 경우의 보조 정렬이다.
     */
    Optional<Challenge> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 그 날짜를 기간에 품은 최종 종료(#50) 챌린지가 있는가 — 지출 잠금 판정용.
     * 날짜 파라미터가 둘인 건 조건이 둘이라서다(start ≤ 날짜, end ≥ 날짜). 아래 isDateLockedByClosedChallenge로 부른다.
     */
    boolean existsByUserIdAndClosedAtIsNotNullAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId, LocalDate onOrAfterStart, LocalDate onOrBeforeEnd);

    /** 잠금 판정 단일 출처 — 같은 날짜를 두 번 넘기는 위 이름을 호출부마다 반복하지 않는다. */
    default boolean isDateLockedByClosedChallenge(Long userId, LocalDate date) {
        return existsByUserIdAndClosedAtIsNotNullAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                userId, date, date);
    }
}
