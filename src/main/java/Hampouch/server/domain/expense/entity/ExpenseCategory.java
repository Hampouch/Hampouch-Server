package Hampouch.server.domain.expense.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** 지출 카테고리 7종 + ETC(직접 입력). label은 화면 표시용이자 customCategory 중복 검사 기준. */
@Getter
@RequiredArgsConstructor
public enum ExpenseCategory {

    DELIVERY("배달"),
    DINING_OUT("외식"),
    CONVENIENCE_STORE("편의점"),
    CAFE("카페"),
    GROCERY("장보기"),
    DESSERT("간식"),
    DRINKING("술자리"),
    ETC("직접 입력");

    private final String label;

    /** customCategory 중복 검사. */
    public static boolean isReservedLabel(String text) {
        return Arrays.stream(values()).anyMatch(c -> c.label.equals(text));
    }
}
