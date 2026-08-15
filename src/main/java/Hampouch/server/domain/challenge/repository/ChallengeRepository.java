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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long>, ExpenseDateLockQuery {

    interface FinalizationCheckTarget {
        Long getChallengeId();

        Long getUserId();
    }

    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    /** IN_PROGRESS 전용. 종료 상태는 여러 건일 수 있으므로 findInProgress를 통해서만 호출한다. */
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
              AND c.status IN (
                    Hampouch.server.domain.challenge.entity.ChallengeStatus.SUCCESS,
                    Hampouch.server.domain.challenge.entity.ChallengeStatus.FAIL
              )
            ORDER BY c.endDate DESC, c.id DESC
            """)
    List<Challenge> findCompletedByUserIdOrderByEndDateDescIdDesc(@Param("userId") Long userId);

    @Query("""
            SELECT c FROM Challenge c
            WHERE c.userId = :userId
              AND c.startDate <= :date
              AND c.endDate >= :date
              AND (c.inactiveFrom IS NULL OR c.inactiveFrom > :date)
            """)
    Optional<Challenge> findActiveOnDate(
            @Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 기간 종료 결과 확정 또는 3일 연속 미입력 자동 취소를 검사할 진행 중 챌린지를 ID 순으로 조회한다.
     * 자동 취소 최소 기간과 미입력 일수는 Challenge의 고정 정책값으로 계산한다.
     * afterId는 직전 배치에서 마지막으로 조회한 챌린지 ID이며,
     * 다음 배치를 해당 ID보다 큰 챌린지부터 이어서 조회하는 커서다.
     */
    @Query("""
            SELECT c.id AS challengeId, c.userId AS userId
            FROM Challenge c
            WHERE c.status = :#{T(Hampouch.server.domain.challenge.entity.ChallengeStatus).IN_PROGRESS}
              AND c.id > :afterId
              AND (
                    c.endDate < :judgmentDate
                    OR (
                        c.durationDays >= :#{T(Hampouch.server.domain.challenge.entity.Challenge).AUTO_CANCEL_MIN_DURATION_DAYS}
                        AND c.startDate <= :#{#judgmentDate.minusDays(T(Hampouch.server.domain.challenge.entity.Challenge).MISSING_INPUT_CANCEL_DAYS)}
                    )
              )
            ORDER BY c.id ASC
            """)
    List<FinalizationCheckTarget> findFinalizationCheckTargetsAfter(
            @Param("judgmentDate") LocalDate judgmentDate,
            @Param("afterId") Long afterId,
            Pageable pageable);

    /** 표시할 종료 상태만 받아 자동 취소 상태가 목록에 섞이지 않게 한다. */
    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /** 중도 포기는 목표 endDate를 보존하므로 직전 종료 판정에는 생성 순서를 사용한다. */
    Optional<Challenge> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 지출 변경과 최종 종료를 직렬화하도록 해당 날짜의 기록 기반 챌린지 행을 모두 잠근다.
     * 포기·자동 취소 챌린지는 대상에서 제외한다.
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
                .anyMatch(Challenge::isExpenseLocked);
    }
}
