package Hampouch.server.domain.expense.service;

import java.time.LocalDate;

public interface ExpenseDateLockQuery {

    boolean isExpenseChangeProhibited(Long userId, LocalDate date);
}
