package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * 자세히 보기 목록의 한 줄 = 지출 1건. 카테고리별/이유별 화면이 항목 카드를 똑같이 그려 record를 공유한다.
 * 하루 목록과 달리 기간 조회라 날짜가 필요하다.
 * categoryLabel/emotionLabel은 커스텀 태그(ETC)일 때만 값이 있고 아니면 키가 생략된다.
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
                expense.getCustomCategory(),
                expense.getEmotion(),
                expense.getCustomEmotion(),
                expense.getPrice()
        );
    }
}
