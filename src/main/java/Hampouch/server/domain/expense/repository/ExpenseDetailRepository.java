package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.ExpenseDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** expense_detail은 expense_id를 PK로 공유하는 optional 1:1 — findByExpenseId로 의도를 드러낸다. */
public interface ExpenseDetailRepository extends JpaRepository<ExpenseDetail, Long> {

    Optional<ExpenseDetail> findByExpenseId(Long expenseId);
}
