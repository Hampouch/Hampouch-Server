package Hampouch.server.domain.expense.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * ETC를 고르면 customEmotion(자유 입력 태그)을 함께 받는다.
 */
@Getter
@RequiredArgsConstructor
public enum ExpenseEmotion {

    STRESS("스트레스"),
    COMPENSATION("보상"),
    CONVENIENCE("귀찮아서"),
    IMPULSE("그냥 먹고 싶어서"),
    ETC("직접 입력");

    private final String label;

    /** customEmotion 중복 검사. */
    public static boolean isReservedLabel(String text) {
        return Arrays.stream(values()).anyMatch(e -> e.label.equals(text));
    }
}
