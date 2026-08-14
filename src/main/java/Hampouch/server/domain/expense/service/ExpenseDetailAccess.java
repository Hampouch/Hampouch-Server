package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * ExpenseDetail get-or-create 동시성 가드 — attach()/updateMemo()가 공유.
 * 없으면 ExpenseDetailInserter(REQUIRES_NEW)로 insert, PK 충돌은 삼키고 재조회한다.
 * insert 결과를 재사용 안 하는 이유: REQUIRES_NEW는 별도 컨텍스트라 커밋과 동시에 detach되기 때문.
 */
@Component
@RequiredArgsConstructor
public class ExpenseDetailAccess {

    private final ExpenseDetailRepository expenseDetailRepository;
    private final ExpenseDetailInserter expenseDetailInserter;

    public ExpenseDetail getOrCreate(Expense expense) {
        return expenseDetailRepository.findByExpenseId(expense.getId())
                .orElseGet(() -> createThenReload(expense));
    }

    private ExpenseDetail createThenReload(Expense expense) {
        try {
            expenseDetailInserter.insert(expense);
        } catch (DataIntegrityViolationException ignored) {
            // 다른 트랜잭션이 먼저 만든 경우 — 아래 재조회에서 그 행을 가져온다.
        }
        return expenseDetailRepository.findByExpenseId(expense.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "ExpenseDetail insert 직후 재조회 실패: expenseId=" + expense.getId()));
    }
}
