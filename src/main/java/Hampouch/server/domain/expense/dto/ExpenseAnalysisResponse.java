package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /expenses/analysis 응답 — 지출 분석 메인 화면. ratio는 모두 정수 퍼센트다.
 * 달력에서 오면 1일~말일, 챌린지 결과에서 오면 챌린지 기간이 그대로 들어온다.
 * 요청 기간을 그대로 되돌려주는 것은 서버가 어떻게 해석했는지 응답만으로 확인하기 위해서다.
 */
public record ExpenseAnalysisResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        long totalAmount,
        List<CategoryAmount> categoryBreakdown,
        List<EmotionAmount> emotionBreakdown,
        List<WeekdayAmount> weekdayBreakdown,
        String weekdayInsight,
        String pouchInsight
) {

    /**
     * ExpenseCategory 8개 전부 금액 내림차순 — 도넛 옆 범례가 0인 항목까지 적으므로
     * 지출 0원인 카테고리도 amount 0으로 포함. 사용자 정의 카테고리는 전부 이 ETC로 접힌다.
     */
    public record CategoryAmount(ExpenseCategory category, long amount, int ratio) {}

    /** ExpenseEmotion 5개 전부 금액 내림차순 — 5개가 전부 화면에 나오므로 지출 0원인 이유도 포함한다. */
    public record EmotionAmount(ExpenseEmotion emotion, long amount, int ratio) {}

    /**
     * 7요일 전부 포함(지출 0인 요일도 amount 0). dayOfWeek는 java.time.DayOfWeek 상수명.
     * 주의: DayOfWeek의 자연 순서는 MONDAY부터라 values() 순서를 그대로 쓰면 화면과 어긋난다.
     * 이 서비스의 주는 일요일에 시작, 아래 DISPLAY_ORDER를 따를 것.
     */
    public record WeekdayAmount(DayOfWeek dayOfWeek, long amount) {

        public static final List<DayOfWeek> DISPLAY_ORDER = List.of(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
        );
    }
}
