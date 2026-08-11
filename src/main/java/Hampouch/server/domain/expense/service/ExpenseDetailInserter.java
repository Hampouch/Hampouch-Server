package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ExpenseDetail이 없을 때 새로 insert만 담당하는 컴포넌트 — REQUIRES_NEW로 별도 트랜잭션에서 실행한다.
 * attach()/updateMemo()는 findByExpenseId로 없음을 확인한 뒤 insert하는 get-or-create 패턴인데,
 * 두 요청이 동시에 같은 expenseId에 대해 없음을 보고 나란히 insert를 시도하면
 * PK 유니크 제약 위반이 날 수 있다.
 * saveAndFlush로 즉시 flush해서 제약 위반이 있다면 이 메서드 안에서 바로 터지게 하고,
 * REQUIRES_NEW라 이 insert 시도만 롤백되며 호출부(ExpenseDetailAccess, 나아가 바깥 트랜잭션)는 영향받지 않는다 —
 * 그래야 ExpenseDetailAccess가 실패를 잡고 재조회로 안전하게 복구할 수 있다.
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
