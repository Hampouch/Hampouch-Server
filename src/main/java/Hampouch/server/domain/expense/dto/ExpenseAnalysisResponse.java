package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /expenses/analysis 응답 — 지출 분석 메인 화면.
 * 달력에서 들어오면 그 달의 1일~말일, 챌린지 결과에서 자세한 식비 지출 분석 보기로 들어오면 챌린지 시작일~종료일이 그대로 넘어온다.
 * 현재 expense domain은 이 요청이 어느 화면에서 왔는지 알 필요가 없음.
 * periodStart/periodEnd를 그대로 되돌려주는 이유: 서버가 요청을 어떻게 해석했는지 응답만 보고 확인할 수 있어야 디버깅이 된다.
 * ratio는 모두 정수(%)
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
