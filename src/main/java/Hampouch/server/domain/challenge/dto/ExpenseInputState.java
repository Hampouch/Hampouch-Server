package Hampouch.server.domain.challenge.dto;

/** 챌린지 기간 중 최근 완료된 날짜의 지출 입력 상태. */
public enum ExpenseInputState {
    NORMAL,
    TWO_DAYS_MISSING,
    AUTO_CANCELLED
}
