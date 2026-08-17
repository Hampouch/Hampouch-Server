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
     * ExpenseDetailAccess의 insert-후-재조회 전용 — 잠금 없는 SELECT는 호출 트랜잭션의 REPEATABLE READ 스냅샷이
     * insert보다 먼저 고정돼 있으면(예: loadOwned()가 먼저 읽은 뒤) 방금 커밋된(자신의 REQUIRES_NEW insert 포함) 행을
     * 못 볼 수 있다. FOR UPDATE는 스냅샷과 무관하게 최신 커밋 데이터를 읽어 이 문제를 피한다.
     * PESSIMISTIC_READ(공유 락)는 이후 같은 트랜잭션의 UPDATE(memo/imageKey)와 맞물려 락 승격 데드락이 나서
     * UserRepository.findByIdForUpdate와 동일하게 처음부터 배타 락(PESSIMISTIC_WRITE)을 잡는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ExpenseDetail d WHERE d.expenseId = :expenseId")
    Optional<ExpenseDetail> findByExpenseIdForUpdate(@Param("expenseId") Long expenseId);
}
