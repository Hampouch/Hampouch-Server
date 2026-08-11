package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * ExpenseDetail get-or-create를 attach()/updateMemo() 양쪽이 공유하는 동시성 안전 진입점.
 * 먼저 findByExpenseId로 조회하고, 없으면 ExpenseDetailInserter(REQUIRES_NEW)로 insert를 시도한다.
 * 동시에 다른 요청이 먼저 insert에 성공했다면 이 insert는 PK 충돌(DataIntegrityViolationException)로
 * 실패하는데, 그 예외는 삼키고 현재(바깥) 트랜잭션의 영속성 컨텍스트로 다시 findByExpenseId를 호출해
 * 상대가 만든 행을 가져온다.
 * insert()가 만든 엔티티를 직접 반환받아 재사용하지 않고 항상 재조회하는 이유: REQUIRES_NEW는 별도
 * 트랜잭션 = 별도 영속성 컨텍스트라서, 그 메서드가 만든 엔티티는 커밋과 동시에 detach된다.
 * detach된 엔티티를 바깥 트랜잭션에서 수정해봐야 flush되지 않으므로, 성공/실패 여부와 무관하게 항상
 * 현재 트랜잭션에서 다시 조회해 관리 상태(managed)인 엔티티를 돌려준다.
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
