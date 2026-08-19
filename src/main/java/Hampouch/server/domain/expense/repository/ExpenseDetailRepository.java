package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.ExpenseDetail;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** expense_detail은 expense_id를 PK로 공유하는 optional 1:1 — findByExpenseId로 의도를 드러낸다. */
public interface ExpenseDetailRepository extends JpaRepository<ExpenseDetail, Long> {

    Optional<ExpenseDetail> findByExpenseId(Long expenseId);

    /**
     * ExpenseDetailAccess의 insert-후-재조회 전용. 잠금 없는 SELECT는 REPEATABLE READ 스냅샷이 먼저 고정돼 있으면
     * 방금 커밋된 행을 못 보지만, FOR UPDATE는 스냅샷과 무관하게 최신 커밋을 읽는다.
     * 공유 락은 뒤따르는 UPDATE와 맞물려 락 승격 데드락이 나므로 처음부터 배타 락을 잡는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ExpenseDetail d WHERE d.expenseId = :expenseId")
    Optional<ExpenseDetail> findByExpenseIdForUpdate(@Param("expenseId") Long expenseId);
}
