package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * 하루치 지출 목록 조회 응답(GET /expenses/day).
 * totalAmount를 목록과 함께 내려주는 이유: 캘린더 일간 화면이 "그날 지출 리스트 + 합계"를 한 화면에서 같이 그리기 때문 —
 * 이 화면 한정으로는 summary 계열 API를 또 호출하게 하는 게 불필요한 라운드트립이라 판단.
 * 월간/주간 캘린더의 "여러 날짜 합계"는 이 DTO 책임 밖 — /expenses/summary 쪽에서 별도 설계 필요(TODO).
 */
public record ExpenseDayListResponse(
        LocalDate date,
        int totalAmount,
        boolean hasRecord,
        List<ExpenseSummary> expenses
) {

    /**
     * 목록 한 줄 = 지출 1건 요약. category/emotion은 enum 그대로이고,
     * customCategory/customEmotion 원문 대신 categoryLabel/emotionLabel(커스텀 태그일 때만 값 존재)을 내려줌.
     * @JsonInclude(NON_NULL)은 categoryLabel/emotionLabel 필드에만 건다
     * → 지출 건너뛰기를 통해 name이 null일 경우 해당 row가 완전히 생략되는 문제를 방지
     */
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
                    expense.getCustomCategory(), // 문자열 컬럼 그대로(이슈 #61) — ETC가 아니면 null이라 NON_NULL 생략 동작 동일
                    expense.getEmotion(),
                    expense.getCustomEmotion()
            );
        }
    }

    /**
     * expenses는 이미 특정 user_id + expense_date로 필터링된 리스트라고 가정(레포지토리 쿼리 책임, 이 팩토리는 변환만).
     * totalAmount는 SUM을 별도 쿼리로 또 날리지 않고 이미 조회한 리스트를 stream으로 합산 —
     * 하루 단위라 건수가 작아서 비용 무시 가능. 월/주 단위 집계처럼 건수가 커지면 SUM을 DB에 위임하는 게 맞고,
     * 이 방식을 그대로 재사용하면 안 됨(리팩토링 시 주의).
     */
    public static ExpenseDayListResponse from(LocalDate date, List<Expense> expenses, boolean hasRecord) {
        int totalAmount = expenses.stream().mapToInt(Expense::getPrice).sum();
        List<ExpenseSummary> summaries = expenses.stream().map(ExpenseSummary::from).toList();
        return new ExpenseDayListResponse(date, totalAmount, hasRecord, summaries);
    }
}
