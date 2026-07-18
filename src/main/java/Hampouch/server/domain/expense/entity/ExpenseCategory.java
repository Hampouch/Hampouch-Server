package Hampouch.server.domain.expense.entity;

/**
 * 지출 카테고리. 확정 7종 + ETC(직접 입력) — 온보딩 챌린지의 "약한 카테고리" 선택지와 동일한 7종을 그대로 사용
 * (ChallengeWeakCategory와 목록 일치, ERD 확정).
 */
public enum ExpenseCategory {

    DELIVERY,
    DINING_OUT,
    CONVENIENCE_STORE,
    CAFE,
    GROCERY,
    DESSERT,
    DRINKING,
    ETC // 이 값일 때만 Expense.customCategory 연관관계가 채워짐
}
