package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.expense.service.ExpenseDateLockQuery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long>, ExpenseDateLockQuery {

    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    /** IN_PROGRESS 전용. 종료 상태는 여러 건일 수 있으므로 findInProgress를 통해서만 호출한다. */
    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);

    default boolean existsInProgress(Long userId) {
        return existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    default Optional<Challenge> findInProgress(Long userId) {
        return findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    /** 표시할 종료 상태만 받아 자동 취소 상태가 목록에 섞이지 않게 한다. */
    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /** 중도 포기는 목표 endDate를 보존하므로 직전 종료 판정에는 생성 순서를 사용한다. */
    Optional<Challenge> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 지출 변경과 최종 종료가 같은 챌린지 행을 잠가 직렬화되도록, 종료 여부를 읽기 전에 행 잠금을 잡는다.
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
