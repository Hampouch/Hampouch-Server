package Hampouch.server.domain.expense.entity;

/**
 * ETC를 고르면 customEmotion(자유 입력 태그)을 함께 받는다.
 */
public enum ExpenseEmotion {

    STRESS,         // 스트레스 해소
    COMPENSATION,   // 자기 보상
    CONVENIENCE,    // 귀찮아서(요리/장보기 대신 간편 소비) — LAZINESS 대신 완곡하게 표현
    IMPULSE,        // 충동 구매
    ETC             // customEmotion과 함께 사용
}
