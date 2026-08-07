package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * 자세히 보기 목록의 한 줄 = 지출 1건.
 * 카테고리별/이유별 두 상세 화면이 항목 카드를 똑같이 그리므로 record를 공유
 * 최상위 식별 필드만 category / emotion으로 다르고 items 안쪽은 완전히 동일.
 * ExpenseDayListResponse.ExpenseSummary와 다르게 특정 기간의 지출 조회라 날짜가 필요
 * categoryLabel/emotionLabel은 커스텀 태그(ETC)일 때만 값이 있고 아니면 키 자체를 생략
 * → 지출 건너뛰기를 통해 name이 null일 경우 해당 row가 완전히 생략되는 문제를 방지
 */
public record ExpenseAnalysisItem(
        Long expenseId,
        LocalDate date,
        String name,
        ExpenseCategory category,
        @JsonInclude(JsonInclude.Include.NON_NULL) String categoryLabel,
        ExpenseEmotion emotion,
        @JsonInclude(JsonInclude.Include.NON_NULL) String emotionLabel,
        int price
) {

    public static ExpenseAnalysisItem from(Expense expense) {
        return new ExpenseAnalysisItem(
                expense.getId(),
                expense.getExpenseDate(),
                expense.getName(),
                expense.getCategory(),
                expense.getCustomCategory(), // 문자열 컬럼 그대로(이슈 #61) — ETC가 아니면 null이라 NON_NULL 생략 동작 동일
                expense.getEmotion(),
                expense.getCustomEmotion(),
                expense.getPrice()
        );
    }
}
