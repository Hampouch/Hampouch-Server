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
     * Spring Data 파생 쿼리는 SUM 같은 집계를 지원하지 않아
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
}
