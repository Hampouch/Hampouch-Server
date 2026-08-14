package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ExpenseDetail insert 전담 — REQUIRES_NEW로 별도 트랜잭션에서 실행한다.
 * 두 요청이 동시에 없음을 보고 나란히 insert하면 PK 충돌이 날 수 있어, saveAndFlush로 즉시 터뜨리고
 * REQUIRES_NEW라 이 insert만 롤백돼 바깥 트랜잭션은 영향받지 않는다(ExpenseDetailAccess가 재조회로 복구).
 */
@Component
@RequiredArgsConstructor
public class ExpenseDetailInserter {

    private final ExpenseDetailRepository expenseDetailRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Expense expense) {
        expenseDetailRepository.saveAndFlush(ExpenseDetail.of(expense, null));
    }
}
