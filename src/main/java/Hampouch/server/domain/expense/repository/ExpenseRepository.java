package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

//조회 시 항상 status 조건 포함 */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 단건 조회 — loadOwned가 조회 후 isOwnedBy로 소유권 검증. */
    Optional<Expense> findByIdAndStatus(Long id, ExpenseStatus status);

    /** GET /expenses/day — 특정 유저의 특정 날짜 지출 목록. */
    List<Expense> findByUser_IdAndExpenseDateAndStatus(Long userId, LocalDate expenseDate, ExpenseStatus status);

    /**
     * GET /expenses/summary/week, /summary/month 공용 — 기간 내 유저의 ACTIVE 지출을 날짜별로 SUM해서 가져온다.
     * 건수가 하루 단위(findByUser_IdAndExpenseDateAndStatus)보다 커질 수 있어 SUM을 DB에 위임
     */
    @Query("""
            SELECT new Hampouch.server.domain.expense.repository.ExpenseDailyTotal(e.expenseDate, SUM(e.price))
            FROM Expense e
            WHERE e.user.id = :userId AND e.status = :status AND e.expenseDate BETWEEN :start AND :end
            GROUP BY e.expenseDate
            ORDER BY e.expenseDate
            """)
    List<ExpenseDailyTotal> sumGroupedByDate(@Param("userId") Long userId, @Param("status") ExpenseStatus status,
                                              @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 파생 쿼리는 SUM 집계를 지원 안 해 @Query로 직접 작성 — coalesce로 지출 없을 때 null 대신 0 반환. */
    @Query("select coalesce(sum(e.price), 0) from Expense e "
            + "where e.user.id = :userId and e.expenseDate = :date and e.status = :status")
    Long sumPriceByUserIdAndExpenseDateAndStatus(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("status") ExpenseStatus status);

    /** getDaySpending()의 hasRecord용 — 합계만으론 기록 없음과 합계 0원을 구분 못해 별도 존재 쿼리로 둔다. */
    boolean existsByUser_IdAndExpenseDateAndStatus(Long userId, LocalDate expenseDate, ExpenseStatus status);

    /** delete()가 User.lastUpdated 재계산 기준을 찾는 용도 — 삭제 중인 지출을 제외한 최신 ACTIVE 지출 1건. */
    Optional<Expense> findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(Long userId, ExpenseStatus status, Long id);

    /** refreshLastUpdated()용 — delete()의 IdNot 버전과 달리 방금 수정된 지출도 포함해서 계산. */
    Optional<Expense> findTopByUser_IdAndStatusOrderByExpenseDateDesc(Long userId, ExpenseStatus status);

    /**
     * 분석 3종(메인/카테고리별/이유별) 공용 — 기간 내 ACTIVE 지출 행을 집계 없이 그대로 꺼낸다.
     * 세 엔드포인트가 같은 행을 보고, 요일 집계는 JPQL에 요일 함수가 없어 DB에 맡기면 dialect에 묶인다.
     * 조회 크기 상한은 기간 상한 100일이 대신하므로 상한을 올릴 땐 여기 부하도 같이 봐야 한다.
     * 동률 행 순서를 DB에 맡기지 않도록 날짜 다음 id로 한 번 더 정렬한다.
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.user.id = :userId AND e.status = :status
              AND e.expenseDate BETWEEN :start AND :end
            ORDER BY e.expenseDate DESC, e.id DESC
            """)
    List<Expense> findPeriodExpenses(@Param("userId") Long userId, @Param("status") ExpenseStatus status,
                                     @Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 배틀 참가자 전원의 today/total 지출을 유저당 한 행으로 집계. Today/Total 토글이 둘 다 쓰므로
     * CASE WHEN으로 같은 GROUP BY에 얹어 쿼리 한 번에 반환한다(참가자 최대 10명이라 실시간 집계로 충분).
     * 지출이 없는 참가자는 행 자체가 안 나오므로 호출부가 0으로 채워야 한다 — 쿼리 안 coalesce로는 못 채운다.
     */
    @Query("""
            SELECT new Hampouch.server.domain.expense.repository.BattleParticipantSpending(
                e.user.id,
                SUM(CASE WHEN e.expenseDate = :today THEN e.price ELSE 0 END),
                SUM(e.price))
            FROM Expense e
            WHERE e.user.id IN :userIds AND e.status = :status AND e.expenseDate BETWEEN :start AND :end
            GROUP BY e.user.id
            """)
    List<BattleParticipantSpending> sumTodayAndTotalByUsers(@Param("userIds") List<Long> userIds,
                                                              @Param("start") LocalDate start,
                                                              @Param("end") LocalDate end,
                                                              @Param("today") LocalDate today,
                                                              @Param("status") ExpenseStatus status);

    /**
     * GET /battles — ONGOING 카드 여러 개의 today/total을 배틀 ID 기준으로 한 번에 집계.
     * 배틀마다 기간이 다르고 한 유저가 여러 배틀에 동시 참가할 수 있어 바깥에서 준 단일 start/end로는
     * 배틀별로 못 나눈다 — 각 배틀의 startDate/endDate를 ON 조인에서 직접 참조하고 end는 오늘로 clamp한다.
     */
    @Query("""
            SELECT new Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending(
                p.battle.id, e.user.id,
                SUM(CASE WHEN e.expenseDate = :today THEN e.price ELSE 0 END),
                SUM(e.price))
            FROM BattleParticipant p
            JOIN Expense e ON e.user.id = p.user.id AND e.status = :status
                AND e.expenseDate BETWEEN p.battle.startDate
                    AND (CASE WHEN :today < p.battle.endDate THEN :today ELSE p.battle.endDate END)
            WHERE p.battle.id IN :battleIds
            GROUP BY p.battle.id, e.user.id
            """)
    List<BattleParticipantBattleSpending> sumTodayAndTotalByBattleIds(@Param("battleIds") List<Long> battleIds,
                                                                       @Param("today") LocalDate today,
                                                                       @Param("status") ExpenseStatus status);
}
