package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.expense.service.ExpenseDateLockQuery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long>, ExpenseDateLockQuery {

    interface FinalizationCheckTarget {
        Long getChallengeId();

        Long getUserId();
    }

    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);

    default boolean existsInProgress(Long userId) {
        return existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    default Optional<Challenge> findInProgress(Long userId) {
        return findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    @Query("""
            SELECT c FROM Challenge c
            WHERE c.userId = :userId
              AND c.startDate <= :date
              AND c.endDate >= :date
              AND (c.inactiveFrom IS NULL OR c.inactiveFrom > :date)
            ORDER BY c.startDate DESC, c.id DESC
            """)
    List<Challenge> findContainingDateOrdered(
            @Param("userId") Long userId, @Param("date") LocalDate date, Pageable pageable);

    default Optional<Challenge> findContainingDate(Long userId, LocalDate date) {
        return findContainingDateOrdered(userId, date, Pageable.ofSize(1)).stream().findFirst();
    }

    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 포기한 챌린지는 목표 endDate를 보존하므로 직전 종료 건은 createdAt으로 고른다.
     * id는 createdAt이 같은 경우의 보조 정렬이다.
     */
    Optional<Challenge> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 지출 변경과 최종 종료가 같은 챌린지 행을 잠가 직렬화되도록, 지출 잠금 여부를 읽기 전에 행 잠금을 잡는다.
     * endReason이 있는 포기·자동 취소 챌린지는 최종 종료 대상이 아니므로 제외한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM Challenge c
            WHERE c.userId = :userId
              AND c.endReason IS NULL
              AND c.startDate <= :date
              AND c.endDate >= :date
            ORDER BY c.startDate ASC, c.id ASC
            """)
    List<Challenge> findRecordBasedChallengesContainingDateForUpdate(
            @Param("userId") Long userId, @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Challenge c WHERE c.id = :id")
    Optional<Challenge> findByIdForUpdate(@Param("id") Long id);

    @Override
    default boolean isExpenseChangeProhibited(Long userId, LocalDate date) {
        return findRecordBasedChallengesContainingDateForUpdate(userId, date).stream()
                .anyMatch(Challenge::isClosed);
    }
}
