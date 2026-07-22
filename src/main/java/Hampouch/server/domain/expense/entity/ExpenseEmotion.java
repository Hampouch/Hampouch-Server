package Hampouch.server.domain.expense.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 지출 시 감정/동기 태그. API 명세 예시에 이미 COMPENSATION이 박혀 있어서 값 이름을 바꾸면 문서와 어긋나므로 유지.
 * ETC를 고르면 customEmotion(자유 입력 태그)을 함께 받는다.
 * label은 화면 표시용 한글 명칭 — ExpenseCategory와 동일하게 customEmotion 중복 검사에도 이 라벨을 사용.
 */
@Getter
@RequiredArgsConstructor
public enum ExpenseEmotion {

    STRESS("스트레스"),          // 스트레스 해소
    COMPENSATION("보상"),
    CONVENIENCE("귀찮아서"),        // 요리/장보기 대신 간편 소비 — LAZINESS 대신 완곡하게 표현
    IMPULSE("그냥 먹고 싶어서"),
    ETC("직접 입력");                 // ETC일 땐 실제 화면엔 이 라벨 대신 customEmotion 값이 노출됨 — 이 라벨은 fallback/내부 비교용

    private final String label;

    /** customEmotion 입력값이 내장 감정 라벨과 이름이 겹치는지 확인 — 중복 검사에 사용. */
    public static boolean isReservedLabel(String text) {
        return Arrays.stream(values()).anyMatch(e -> e.label.equals(text));
    }
}
