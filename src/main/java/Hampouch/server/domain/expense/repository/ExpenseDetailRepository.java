package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.ExpenseDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * expense_detail은 expense_id를 PK로 공유하는 진짜 optional 1:1.,
 * findById 대신 findByExpenseId로 이름을 맞춰 Expense의 PK로 찾는다는 의도를 드러낸다.
 */
public interface ExpenseDetailRepository extends JpaRepository<ExpenseDetail, Long> {

    Optional<ExpenseDetail> findByExpenseId(Long expenseId);
}
