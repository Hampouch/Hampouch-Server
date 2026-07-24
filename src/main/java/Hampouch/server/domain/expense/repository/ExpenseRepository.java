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
}
