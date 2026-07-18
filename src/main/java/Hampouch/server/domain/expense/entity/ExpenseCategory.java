package Hampouch.server.domain.expense.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 지출 카테고리. 확정 7종 + ETC(직접 입력) — 온보딩 챌린지의 "약한 카테고리" 선택지와 동일한 7종을 그대로 사용
 * (ChallengeWeakCategory와 목록 일치, ERD 확정).
 * label은 화면 표시용 한글 명칭 — customCategory 중복 검사(ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED)도
 * enum 이름(영문)이 아니라 이 라벨과 비교해야 실제로 의미 있는 검사가 됨(0718 결정).
 */
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
    ETC("직접 입력"); // ETC일 땐 실제 화면엔 이 라벨 대신 customCategory 값이 노출됨(GET /expenses/day 응답 예시 기준) — 이 라벨은 fallback/내부 비교용

    private final String label;

    /** customCategory 입력값이 내장 카테고리 라벨과 이름이 겹치는지 확인 — 중복 검사에 사용. */
    public static boolean isReservedLabel(String text) {
        return Arrays.stream(values()).anyMatch(c -> c.label.equals(text));
    }
}
