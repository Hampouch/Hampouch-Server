package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/** 하루치 지출 목록 응답(GET /expenses/day). totalAmount를 함께 내려 일간 화면이 summary API를 또 호출하지 않게 한다. */
public record ExpenseDayListResponse(
        LocalDate date,
        int totalAmount,
        boolean hasRecord,
        List<ExpenseSummary> expenses
) {

    /** 목록 한 줄 = 지출 1건 요약. categoryLabel/emotionLabel은 커스텀 태그일 때만 값이 있어 NON_NULL로 생략한다. */
    public record ExpenseSummary(
            Long expenseId,
            String name,
            int price,
            ExpenseCategory category,
            @JsonInclude(JsonInclude.Include.NON_NULL) String categoryLabel,
            ExpenseEmotion emotion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String emotionLabel
    ) {
        public static ExpenseSummary from(Expense expense) {
            return new ExpenseSummary(
                    expense.getId(),
                    expense.getName(),
                    expense.getPrice(),
                    expense.getCategory(),
                    expense.getCustomCategory(), // 문자열 컬럼 그대로 — ETC 아니면 null이라 NON_NULL 생략 동일
                    expense.getEmotion(),
                    expense.getCustomEmotion()
            );
        }
    }

    /** totalAmount는 이미 조회한 리스트를 stream 합산 — 하루 단위라 비용 무시 가능(월/주 집계엔 재사용 금지). */
    public static ExpenseDayListResponse from(LocalDate date, List<Expense> expenses, boolean hasRecord) {
        int totalAmount = expenses.stream().mapToInt(Expense::getPrice).sum();
        List<ExpenseSummary> summaries = expenses.stream().map(ExpenseSummary::from).toList();
        return new ExpenseDayListResponse(date, totalAmount, hasRecord, summaries);
    }
}
