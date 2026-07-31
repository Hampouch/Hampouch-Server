package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * status를 항상 조건에 포함시키는 이유: Expense는 soft delete 대상이라 findById만 쓰면
 * 이미 삭제된 행이 그대로 조회돼 GET/PUT/DELETE가 EXPENSE_NOT_FOUND를 내야 할 상황에서 실수로
 * 삭제된 데이터를 돌려주게 된다 — Challenge에는 이 문제가 없어 loadOwned가 findById를
 * 그대로 쓰지만, Expense는 반드시 findByIdAndStatus로 대체해야 함.
 */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 단건 조회(GET/PUT/DELETE 공통 진입점) — service의 loadOwned가 이걸로 조회 후 isOwnedBy로 소유권 검증. */
    Optional<Expense> findByIdAndStatus(Long id, ExpenseStatus status);

    /**
     * GET /expenses/day — 특정 유저의 특정 날짜 지출 목록, 삭제분 제외.
     * ChallengeDayRepository의 언더스코어 경로 컨벤션과 동일(User_Id = user 연관관계를 타고 들어간 id).
     */
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
     /* Spring Data 파생 쿼리는 SUM 같은 집계를 지원하지 않아
     * sumPriceByUserIdAndExpenseDateAndStatus를 @Query로 직접 작성하고, coalesce로 감싸 그 날짜에
     * 지출이 하나도 없을 때도 null 대신 0을 반환하게 한다.
     */

    @Query("select coalesce(sum(e.price), 0) from Expense e "
            + "where e.user.id = :userId and e.expenseDate = :date and e.status = :status")
    int sumPriceByUserIdAndExpenseDateAndStatus(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("status") ExpenseStatus status);

    /**
     * ExpenseService.getDaySpending()에서 DaySpending.hasRecord를 채우는 용도 — 합계(sum)만으로는
     * 그 날짜에 기록 자체가 없음과 그 날짜 기록의 합계가 0원임을 구분할 수 없어 별도 존재 확인 쿼리로 둔다.
     */
    boolean existsByUser_IdAndExpenseDateAndStatus(Long userId, LocalDate expenseDate, ExpenseStatus status);

    /**
     * ExpenseService.delete()가 User.lastUpdated를 되돌릴 기준을 찾는 용도
     * 지금 삭제 중인 지출을 뺀 나머지 지출 중 expenseDate가 가장 최근인 1건을 찾는다.
     * -> 3일 이상 지출 기록이 비면 무효화라는 규칙은 지출 발생 날짜를 기준으로 하므로, 언제 등록됐는지는 무관
     * 지운 지출이 유일한 지출이었다면 서비스가 User.lastUpdated를 User.createdAt(계정 생성일)로 대신 되돌린다.
     */
    Optional<Expense> findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(Long userId, ExpenseStatus status, Long id);

    /**
     * ExpenseService.refreshLastUpdated()가 expense를 update() 이후 User.lastUpdated를 다시 계산할 때 쓰는 용도.
     * delete()가 쓰는 AndIdNot 버전과 달리, 방금 생성/수정된 지출도 그대로 포함해서 계산해야 하므로 제외 조건이 없다.
     */
    Optional<Expense> findTopByUser_IdAndStatusOrderByExpenseDateDesc(Long userId, ExpenseStatus status);
}
