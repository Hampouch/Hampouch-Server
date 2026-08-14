package Hampouch.server.domain.expense.entity;

/** 지출 활성/삭제 상태 — soft delete. 조회 시 반드시 ACTIVE로 필터링. */
public enum ExpenseStatus {

    ACTIVE,
    DELETED
}
