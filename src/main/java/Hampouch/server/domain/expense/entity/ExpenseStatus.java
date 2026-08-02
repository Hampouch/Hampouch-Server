package Hampouch.server.domain.expense.entity;

/**
 * 지출의 활성/삭제 상태. DELETE API는 물리 삭제 대신 status=DELETED로 처리하는 soft delete
 * 삭제 이력을 남기고, 조회 시 반드시 status=ACTIVE로 필터링해야 삭제된 지출이 목록/합계 계산에 섞여 들어가지 않는다.
 */
public enum ExpenseStatus {

    ACTIVE,
    DELETED
}
