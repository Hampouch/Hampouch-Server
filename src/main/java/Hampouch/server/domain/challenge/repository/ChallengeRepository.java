package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 챌린지 저장소 — 파생 쿼리는 선언만 하면 스프링 데이터가 구현한다.
 */
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    /** status까지 보는 이유: 끝난 챌린지(SUCCESS/FAIL)도 DB에 남아, userId만 세면 과거에 챌린지 한 유저가 새로 못 만든다. */
    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    /**
     * IN_PROGRESS 조회용(상태 고정판 findInProgress로 감싼다). userId+IN_PROGRESS가 "0 또는 1"인 건
     * 쿼리가 아니라 도메인 규칙(동시 진행 1개)이 보장한다 — 2행 이상이면 Spring Data가 500(IncorrectResultSize)을 던진다.
     * SUCCESS/FAIL은 유저당 여럿이라 List를 반환하는 아래 히스토리 쿼리 몫이다.
     */
    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);

    /** 파생 쿼리 이름엔 값(IN_PROGRESS)을 못 박아 default로 상태를 고정한다 — 챌린지 서비스는 이 둘로 진행 중을 묻는다. */
    default boolean existsInProgress(Long userId) {
        return existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    default Optional<Challenge> findInProgress(Long userId) {
        return findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    /**
     * 지난 챌린지 리스트(#4) — 최근 종료(endDate 내림차순)가 먼저. 보여줄 상태를 In(SUCCESS, FAIL)으로 명시하는 이유(함정):
     * Not(IN_PROGRESS)으로 짜면 나중에 추가된 VOID(자동 취소)가 저절로 리스트에 흘러든다. endDate 동률은 id 내림차순으로 안정 정렬.
     */
    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 직전 종료 챌린지 1건 — 휴식기 홈 keptRecords(#8). 정렬이 endDate가 아니라 createdAt인 이유(함정):
     * 포기 챌린지는 endDate가 원래 목표 기간이라 미래일 수 있어, endDate 내림차순은 옛날에 포기한 긴 챌린지를 최근으로 오판한다.
     * 동시 진행 1개라 생성순이 곧 종료순 — id는 같은 시각일 때의 보조 기준.
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
