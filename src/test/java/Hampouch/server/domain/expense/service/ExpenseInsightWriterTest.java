package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.service.ChallengeProgress;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.CategoryAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.EmotionAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.WeekdayAmount;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse.MonthlyAmount;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseInsightWriter.PeriodFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인사이트 문구 분기. 의존성이 없는 순수 계산이라 Mockito도 스프링 컨텍스트도 필요 없다 -
 * 이 클래스를 서비스에서 떼어낸 이유가 여기 있다(집계를 목으로 만들 필요 없이 문장만 본다).
 *
 * 문자열을 그대로 비교하는 건 이 값이 응답 본문에 그대로 실려 나가는 프론트 계약이기 때문이다.
 * 문구가 바뀌면 테스트가 깨져야 맞다.
 */
class ExpenseInsightWriterTest {

    /** 달력에서 온 조회(1일~말일)의 기본 기간. 31일이라 전반/후반이 15일 + 16일로 갈린다. */
    private static final LocalDate MONTH_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 5, 31);

    private final ExpenseInsightWriter writer = new ExpenseInsightWriter();

    // ---------- weekdayInsight (요일 차트 배지) ----------

    @Test
    @DisplayName("지출이 없으면 요일 배지는 분석을 제공하지 않는다고 말한다")
    void weekdayInsight_noExpense() {
        assertThat(writer.weekdayInsight(weekdays(0, 0, 0, 0, 0, 0, 0), 0))
                .isEqualTo("지출 기록이 없어 요일 분석을 제공하지 않아요!");
    }

    @Test
    @DisplayName("1위가 압도적이면 한 요일만 적고, 이때는 '금'이 아니라 '금요일'로 쓴다")
    void weekdayInsight_singleDay() {
        assertThat(writer.weekdayInsight(weekdays(0, 0, 0, 0, 0, 5_000, 3_000), 8_000))
                .isEqualTo("금요일 지출이 가장 많아요");
    }

    /**
     * 시안 값 그대로(금 102,000 / 토 105,800). 금액순이면 토가 먼저지만 시안은 금, 토라고 적혀 있다 -
     * 배지의 나열 순서는 금액이 아니라 차트의 요일 순서를 따라야 눈이 같이 움직인다.
     */
    @Test
    @DisplayName("1, 2위가 엎치락뒤치락하면 둘 다 적고 나열 순서는 금액순이 아니라 요일 순서다")
    void weekdayInsight_twoDaysInDisplayOrder() {
        assertThat(writer.weekdayInsight(weekdays(42_000, 32_000, 41_000, 38_000, 51_000, 102_000, 105_800), 411_800))
                .isEqualTo("금, 토 지출이 가장 많아요");
    }

    @Test
    @DisplayName("금액이 완전히 같아도 요일 표시 순서로 끊어 같은 데이터면 항상 같은 문구가 나온다")
    void weekdayInsight_tieBrokenByDisplayOrder() {
        assertThat(writer.weekdayInsight(weekdays(5_000, 0, 0, 0, 0, 0, 5_000), 10_000))
                .isEqualTo("일, 토 지출이 가장 많아요");
    }

    // ---------- pouchInsight (햄포치 분석 카드) ----------

    /**
     * 문장 다섯 개가 통째로 비교된다. 문장별로 쪼개 검증하지 않는 이유는 이 값이 응답에 그대로 실려
     * 카드 하나에 렌더링되기 때문 - 각 문장이 맞아도 이어 붙였을 때 어색하면 그건 버그다.
     */

    @Test
    @DisplayName("지출이 없으면 햄포치 카드도 분석을 제공하지 않는다고 말한다")
    void pouchInsight_noExpense() {
        assertThat(writer.pouchInsight(null)).isEqualTo("지출 기록이 없어 햄포치 분석을 제공하지 않아요!");
    }

    /** 서비스는 총액 0원이면 null을 넘기지만, 재료가 왔는데 총액만 0인 경우도 같은 문구로 닫는다. */
    @Test
    @DisplayName("재료가 있어도 총액이 0원이면 분석을 만들지 않는다")
    void pouchInsight_zeroTotal() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 0,
                categories(0, Map.of()), emotions(0, Map.of()), weekdays(0, 0, 0, 0, 0, 0, 0),
                null, null, 0, 0, 0, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo("지출 기록이 없어 햄포치 분석을 제공하지 않아요!");
    }

    /**
     * 기본형. 감정 비중이 가장 먼저 걸려 3번째 문장을 가져가고, 5문장이 전부 나온다.
     * 2번째 문장의 이유('스트레스')는 기간 전체 1위가 아니라 1위 카테고리(카페) 안에서의 1위다.
     */
    @Test
    @DisplayName("감정 비중이 기준을 넘으면 그 축으로 3, 4번째 문장을 만들고 다섯 문장이 다 나온다")
    void pouchInsight_emotionAxis() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 8_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(2_000, 0, 0, 0, 0, 5_000, 3_000),
                ExpenseEmotion.STRESS, null, 0,
                10_000, 0, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페가 70%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 80%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 감정이 밋밋하면 상위 2개 카테고리 합으로 갈아탄다. 동시에 기간 라벨(1일~말일이 아니라 챌린지 기간),
     * 받침 있는 라벨의 조사(배달이 / 배달과), 후반부가 늘었을 때의 마지막 문장까지 한 번에 지난다.
     */
    @Test
    @DisplayName("감정이 기준 미달이면 상위 2개 카테고리 합으로 갈아탄다")
    void pouchInsight_topTwoCategoryAxis() {
        PeriodFacts facts = new PeriodFacts(
                LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 17), 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 4_000, ExpenseCategory.CAFE, 2_500,
                        ExpenseCategory.GROCERY, 2_000, ExpenseCategory.DESSERT, 1_500)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_500, ExpenseEmotion.COMPENSATION, 3_500,
                        ExpenseEmotion.CONVENIENCE, 3_000)),
                weekdays(1_000, 2_000, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.CONVENIENCE, null, 0,
                4_000, 6_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "이번 챌린지 기간 식비는 10,000원이에요."
                        + " 그 중 배달이 40%로 가장 컸고, 대부분 '귀찮아서' 때문이었어요."
                        + " 배달과 카페 두 곳이 전체의 65%를 차지했어요."
                        + " 배달 음식을 줄여보는 건 어떨까요?"
                        + " 무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?");
    }

    /**
     * 금액 축이 둘 다 밋밋하면 요일 쏠림으로 내려간다.
     * 1위 카테고리가 30%에 못 미쳐 2번째 문장도 이유 없이 상위 2개를 나열만 하는 형태가 된다.
     */
    @Test
    @DisplayName("카테고리도 밋밋하면 요일 쏠림을 말한다")
    void pouchInsight_weekdayAxis() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 2_000, ExpenseCategory.CAFE, 2_000,
                        ExpenseCategory.GROCERY, 2_000, ExpenseCategory.DESSERT, 2_000,
                        ExpenseCategory.DRINKING, 2_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_000, ExpenseEmotion.COMPENSATION, 3_000,
                        ExpenseEmotion.CONVENIENCE, 2_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(0, 3_000, 2_000, 2_000, 1_000, 1_000, 1_000),
                ExpenseEmotion.STRESS, null, 0,
                6_000, 4_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 배달, 카페가 가장 많은 비중을 차지했어요."
                        + " 월요일에만 전체의 30%가 몰렸어요."
                        + " 월요일엔 미리 한 끼를 정해두면 지출이 덜 흔들려요."
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 금액으로는 아무 데도 안 튀는데 편의점만 자주 들르는 경우 - 금액 순위로는 절대 안 보이는 축이다.
     * 임계값이 기간에 비례하므로 31일 조회인 이 케이스는 10회를 넘겨야 축이 걸린다.
     */
    @Test
    @DisplayName("금액 축이 모두 밋밋하면 마지막으로 기록 건수를 말한다")
    void pouchInsight_frequencyAxis() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 2_000, ExpenseCategory.CONVENIENCE_STORE, 2_000,
                        ExpenseCategory.CAFE, 2_000, ExpenseCategory.GROCERY, 2_000,
                        ExpenseCategory.DESSERT, 2_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_000, ExpenseEmotion.COMPENSATION, 3_000,
                        ExpenseEmotion.CONVENIENCE, 2_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.STRESS, ExpenseCategory.CONVENIENCE_STORE, 10,
                4_000, 6_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 배달, 편의점이 가장 많은 비중을 차지했어요."
                        + " 편의점이 10번으로 가장 자주 기록됐어요."
                        + " 한 번 쓰는 금액은 작아도 횟수가 쌓이면 커져요. 편의점 횟수부터 줄여볼까요?"
                        + " 무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?");
    }

    /*
     * 아래 빈도 축 경계 넷만 카드 전체가 아니라 3번째 문장으로 검증한다. 카드 전체 문구 계약은 바로 위
     * pouchInsight_frequencyAxis가 이미 못박고 있고, 여기서 갈리는 것은 어느 축이 3번째 문장을
     * 가져가는지 하나뿐이라 나머지 네 문장까지 적으면 무엇이 경계인지가 오히려 묻힌다.
     */

    /**
     * 이 변경의 핵심. 같은 3회라도 7일이면 "자주"지만 14일이면 아니다.
     * 고정 건수였을 때는 기간과 무관하게 둘 다 아니었다.
     */
    @Test
    @DisplayName("빈도 축 임계값은 기간에 비례해서 같은 건수도 기간이 길면 미달이 된다")
    void pouchInsight_frequencyAxis_thresholdScalesWithPeriod() {
        LocalDate start = LocalDate.of(2026, 5, 4);

        // 7일 -> max(3, 7/3) = 3회
        assertThat(writer.pouchInsight(flatFactsWithFrequency(start, LocalDate.of(2026, 5, 10), 3)))
                .contains("편의점이 3번으로 가장 자주 기록됐어요.");

        // 14일 -> max(3, 14/3) = 4회. 같은 3회가 여기서는 미달이다.
        assertThat(writer.pouchInsight(flatFactsWithFrequency(start, LocalDate.of(2026, 5, 17), 3)))
                .contains("특별히 튀는 항목 없이 고르게 쓰셨어요.")
                .doesNotContain("가장 자주 기록됐어요.");
        assertThat(writer.pouchInsight(flatFactsWithFrequency(start, LocalDate.of(2026, 5, 17), 4)))
                .contains("편의점이 4번으로 가장 자주 기록됐어요.");
    }

    /** 기간이 짧다고 2회를 습관이라 부르지 않도록 잡아 둔 하한(FREQUENT_COUNT_MIN). 7일 / 3 = 2회지만 미달이다. */
    @Test
    @DisplayName("기간을 나눈 값이 하한보다 작으면 하한 건수를 쓴다")
    void pouchInsight_frequencyAxis_countFloor() {
        assertThat(writer.pouchInsight(
                flatFactsWithFrequency(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 10), 2)))
                .contains("특별히 튀는 항목 없이 고르게 쓰셨어요.")
                .doesNotContain("가장 자주 기록됐어요.");
    }

    /** 한 달 조회는 10회를 넘겨야 한다 - 고정 8회 기준이었다면 9회로도 걸렸을 자리다. */
    @Test
    @DisplayName("한 달 조회에서는 임계값 직전 건수로 빈도 축이 걸리지 않는다")
    void pouchInsight_frequencyAxis_justBelowMonthlyThreshold() {
        assertThat(writer.pouchInsight(flatFactsWithFrequency(MONTH_START, MONTH_END, 9)))
                .contains("특별히 튀는 항목 없이 고르게 쓰셨어요.")
                .doesNotContain("가장 자주 기록됐어요.");
    }

    /**
     * 기간이 일주일이 안 되면 건수가 아무리 많아도 축 자체를 보지 않는다.
     * 3일 동안 편의점 20번은 습관이 아니라 그 며칠의 사정이라, 줄여보라는 조언의 근거가 되지 못한다.
     */
    @Test
    @DisplayName("기간이 7일 미만이면 건수가 많아도 빈도 축을 건너뛴다")
    void pouchInsight_frequencyAxis_skippedWhenPeriodTooShort() {
        assertThat(writer.pouchInsight(
                flatFactsWithFrequency(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 6), 20)))
                .contains("특별히 튀는 항목 없이 고르게 쓰셨어요.")
                .doesNotContain("가장 자주 기록됐어요.");
    }

    /**
     * 네 축이 다 미달이면 3번째 문장이 고르게 썼다는 말로 닫히고 4번째 제안이 아예 없어 4문장이 된다.
     * 이 분기가 없으면 고르게 쓴 사람에게도 배달을 줄여보라는 사실이 아닌 조언이 나간다.
     */
    @Test
    @DisplayName("어느 축도 기준을 못 넘으면 제안 문장 없이 네 문장으로 끝난다")
    void pouchInsight_noAxisReachesThreshold() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 2_000, ExpenseCategory.CONVENIENCE_STORE, 2_000,
                        ExpenseCategory.CAFE, 2_000, ExpenseCategory.GROCERY, 2_000,
                        ExpenseCategory.DESSERT, 2_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_000, ExpenseEmotion.COMPENSATION, 3_000,
                        ExpenseEmotion.CONVENIENCE, 2_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.STRESS, ExpenseCategory.CONVENIENCE_STORE, 5,
                4_000, 6_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 배달, 편의점이 가장 많은 비중을 차지했어요."
                        + " 특별히 튀는 항목 없이 고르게 쓰셨어요."
                        + " 무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?");
    }

    /**
     * 29%와 30%를 나란히 두는 이유는 경계가 상수 하나(CATEGORY_FOCUS_PERCENT)에 매여 있어
     * 그 값을 조정할 때 어느 쪽이 움직였는지 바로 보이게 하려는 것이다.
     */
    @Test
    @DisplayName("1위 카테고리가 정확히 30%면 이유까지 붙인다 (경계 포함)")
    void pouchInsight_categoryExactlyAtThreshold() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 3_000, ExpenseCategory.CAFE, 1_900,
                        ExpenseCategory.GROCERY, 1_800, ExpenseCategory.DESSERT, 1_700,
                        ExpenseCategory.DRINKING, 1_600)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_500, ExpenseEmotion.COMPENSATION, 3_500,
                        ExpenseEmotion.CONVENIENCE, 3_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.COMPENSATION, null, 0,
                6_000, 4_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 배달이 30%로 가장 컸고, 대부분 '보상' 때문이었어요."
                        + " 특별히 튀는 항목 없이 고르게 쓰셨어요."
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    @Test
    @DisplayName("1위 카테고리가 29%면 이유를 지어내지 않고 상위 둘을 나열만 한다")
    void pouchInsight_categoryJustBelowThreshold() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 2_900, ExpenseCategory.CAFE, 2_000,
                        ExpenseCategory.GROCERY, 1_800, ExpenseCategory.DESSERT, 1_700,
                        ExpenseCategory.DRINKING, 1_600)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_500, ExpenseEmotion.COMPENSATION, 3_500,
                        ExpenseEmotion.CONVENIENCE, 3_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.COMPENSATION, null, 0,
                6_000, 4_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 배달, 카페가 가장 많은 비중을 차지했어요."
                        + " 특별히 튀는 항목 없이 고르게 쓰셨어요."
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * ETC 라벨(직접 입력)은 그대로 쓰면 직접 입력이 가장 컸고 식이 되어 문장이 안 된다.
     * 카테고리는 직접 입력한 항목, 이유는 직접 입력한 이유로 바꿔 넣는다.
     */
    @Test
    @DisplayName("ETC는 카테고리도 이유도 라벨을 그대로 쓰지 않는다")
    void pouchInsight_etcLabels() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.ETC, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_500, ExpenseEmotion.COMPENSATION, 3_500,
                        ExpenseEmotion.ETC, 3_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.ETC, null, 0,
                6_000, 4_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 직접 입력한 항목이 70%로 가장 컸고, 대부분 '직접 입력한 이유' 때문이었어요."
                        + " 직접 입력한 항목과 배달 두 곳이 전체의 100%를 차지했어요."
                        + " 어디에 돈이 나갔는지 한 번 살펴볼까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 기간이 일주일이 안 되면 반으로 갈라도 표본이 안 되므로 마지막 문장을 만들지 않는다.
     * 여기서는 감정 축이 걸려 네 문장으로 끝난다.
     */
    @Test
    @DisplayName("기간이 7일 미만이면 유지/조정 문장을 붙이지 않는다")
    void pouchInsight_periodTooShortForClosing() {
        PeriodFacts facts = new PeriodFacts(
                LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 9), 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 8_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(2_000, 0, 0, 0, 0, 5_000, 3_000),
                ExpenseEmotion.STRESS, null, 0,
                10_000, 0, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "이번 챌린지 기간 식비는 10,000원이에요."
                        + " 그 중 카페가 70%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 80%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?");
    }

    /**
     * 잘 지키고 있는 진행 중 챌린지가 조회 기간에 걸쳐 있으면 마지막 문장이 통째로 갈린다.
     * 전반/후반 추세로는 "지금 흐름 그대로면 충분해요"가 나올 재료(후반이 0원)인데도 그 계산 자체를 하지 않는다 —
     * 목표를 이미 잡아 둔 사람에게 기간 내부 추세로 목표를 다시 잡으라 말하는 게 이 분기가 없앤 문제다.
     */
    @Test
    @DisplayName("잘 지키는 중인 챌린지가 있으면 마지막 문장이 다음 챌린지 제안으로 바뀐다")
    void pouchInsight_closingReplacedByChallengeSuggestion() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 8_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(2_000, 0, 0, 0, 0, 5_000, 3_000),
                ExpenseEmotion.STRESS, null, 0,
                10_000, 0, ChallengeProgress.ON_TRACK);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페가 70%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 80%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 다음 챌린지에서 식비를 살짝만 줄여보는 것도 추천해요.");
    }

    /**
     * 같은 6일 기간이라도 잘 지키는 중인 챌린지가 걸려 있으면 마지막 문장이 붙는다.
     * CLOSING_MIN_DAYS는 반으로 갈랐을 때 표본이 되느냐는 조건이었는데, 이 문구는 숫자를 말하지 않아
     * 짧은 기간에도 틀릴 여지가 없다 - 챌린지 기간이 짧을수록 오히려 이 조언이 필요하다.
     */
    @Test
    @DisplayName("기간이 7일 미만이어도 챌린지 문장은 붙는다")
    void pouchInsight_challengeSuggestionIgnoresMinDays() {
        PeriodFacts facts = new PeriodFacts(
                LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 9), 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 8_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(2_000, 0, 0, 0, 0, 5_000, 3_000),
                ExpenseEmotion.STRESS, null, 0,
                10_000, 0, ChallengeProgress.ON_TRACK);

        assertThat(writer.pouchInsight(facts))
                .endsWith(" 다음 챌린지에서 식비를 살짝만 줄여보는 것도 추천해요.");
    }

    /**
     * 같은 챌린지라도 예산보다 앞서 쓰고 있으면 다음이 아니라 지금 남은 기간을 이야기한다.
     * 전반/후반 추세로 가면 "무리한 목표보다 지킬 수 있는 선부터"가 나올 재료인데, 목표는 이미 정해 둔 상태라
     * 다시 정하라는 말이 성립하지 않는다.
     */
    @Test
    @DisplayName("챌린지 예산보다 앞서 쓰고 있으면 남은 기간을 줄여보자는 문장으로 닫는다")
    void pouchInsight_closingWarnsWhenOverPace() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 7_000, ExpenseCategory.DELIVERY, 3_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 8_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(2_000, 0, 0, 0, 0, 5_000, 3_000),
                ExpenseEmotion.STRESS, null, 0,
                10_000, 0, ChallengeProgress.OVER_PACE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페가 70%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 80%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 이번 챌린지는 예산보다 조금 빠르게 쓰고 있어요. 남은 기간엔 하루 한 끼만 줄여볼까요?");
    }

    /**
     * 방어적 분기를 못박아 두는 테스트. 지금 서비스는 topCategoryEmotion을 null로 넘기지 않는다
     * (Expense.emotion이 non-null이라 1위 카테고리에 금액이 있으면 이유도 반드시 있다).
     * 이유를 선택값으로 바꾸는 변경이 들어와도 2번째 문장이 깨지지 않아야 해서 남겨 둔다.
     */
    @Test
    @DisplayName("이유를 못 구했고 다른 카테고리에 지출이 없으면 나열 대신 한 곳만 지목한다")
    void pouchInsight_singleCategoryWithoutReason() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 10_000)),
                emotions(10_000, Map.of(ExpenseEmotion.STRESS, 10_000)),
                weekdays(0, 0, 0, 0, 0, 10_000, 0),
                null, null, 0,
                10_000, 0, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페에 지출이 몰려 있었어요."
                        + " '스트레스' 때문에 쓴 돈이 전체의 100%나 돼요."
                        + " 먹는 것 말고 다른 스트레스 해소법을 정해볼까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    /**
     * 리뷰 지적 사항: 지출이 카테고리 하나에만 몰리면(2위가 0원) second.amount() > 0 조건 때문에
     * 두 곳 하이라이트 분기 전체가 건너뛰어지고, 감정/요일/빈도 축도 기준 미달이면 3번째 문장이
     * 특별히 튀는 항목 없이 고르게 쓰셨어요로 떨어져 2번째 문장과 모순됐다.
     * 이 테스트는 그 모순을 막는 ExpenseInsightWriter.pickHighlight()의 second.amount() == 0 분기를 고정한다.
     */
    @Test
    @DisplayName("한 카테고리에 지출이 100% 몰리고 다른 축도 다 미달이면 그 카테고리를 짚어 말한다")
    void pouchInsight_singleCategoryDominatesWithNoOtherAxis() {
        PeriodFacts facts = new PeriodFacts(
                MONTH_START, MONTH_END, 10_000,
                categories(10_000, Map.of(ExpenseCategory.CAFE, 10_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_000, ExpenseEmotion.COMPENSATION, 3_000,
                        ExpenseEmotion.CONVENIENCE, 2_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.STRESS, null, 0,
                6_000, 4_000, ChallengeProgress.NONE);

        assertThat(writer.pouchInsight(facts)).isEqualTo(
                "5월 식비는 10,000원이에요."
                        + " 그 중 카페가 100%로 가장 컸고, 대부분 '스트레스' 때문이었어요."
                        + " 카페 한 곳에서만 전체의 100%를 썼어요."
                        + " 카페 대신 집에서 한 잔 어떨까요?"
                        + " 지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!");
    }

    // ---------- firstHalfEnd (서비스와 공유하는 경계 규칙) ----------

    /**
     * 서비스가 금액을 전반/후반으로 갈라 담을 때 쓰는 유일한 기준이라 여기서 못박는다.
     * 이 규칙이 서비스에 복사되면 경계가 하루 어긋나도 아무도 모른다.
     */
    @Test
    @DisplayName("홀수 일수면 전반부가 하루 짧다")
    void firstHalfEnd_oddLength() {
        assertThat(ExpenseInsightWriter.firstHalfEnd(MONTH_START, MONTH_END))
                .isEqualTo(LocalDate.of(2026, 5, 15)); // 31일 = 전반 15일 + 후반 16일
    }

    @Test
    @DisplayName("짝수 일수면 정확히 반으로 갈린다")
    void firstHalfEnd_evenLength() {
        assertThat(ExpenseInsightWriter.firstHalfEnd(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 17)))
                .isEqualTo(LocalDate.of(2026, 5, 10)); // 14일 = 전반 7일 + 후반 7일
    }

    // ---------- trendInsight (월별 추이) ----------

    @Test
    @DisplayName("6개월 내내 지출이 없으면 추이 문구도 분석을 제공하지 않는다고 말한다")
    void trendInsight_noExpense() {
        assertThat(writer.trendInsight(trend(0, 0, 0, 0, 0, 0), null))
                .isEqualTo("지출 기록이 없어 햄포치 분석을 제공하지 않아요!");
    }

    /** 시안 문장 그대로. 연도를 안 적어도 되는 건 창이 6개월이라 같은 월 숫자가 두 번 나올 수 없어서다. */
    @Test
    @DisplayName("증가한 달은 최고 지출 월과 증감률을 함께 적는다")
    void trendInsight_increased() {
        assertThat(writer.trendInsight(trend(1_352_000, 352_000, 398_000, 421_000, 388_000, 411_800), 6))
                .isEqualTo("가장 식비가 많이 나온 달은 12월, 이번 달은 지난달에 비해 6% 증가했어요.");
    }

    @Test
    @DisplayName("줄어든 달은 부호를 떼고 적는다 ('-6% 줄었어요'는 이중 부정이라 읽히지 않는다)")
    void trendInsight_decreased() {
        assertThat(writer.trendInsight(trend(0, 0, 0, 0, 30_000, 20_000), -33))
                .isEqualTo("가장 식비가 많이 나온 달은 4월, 이번 달은 지난달에 비해 33% 줄었어요.");
    }

    @Test
    @DisplayName("증감이 0%면 숫자를 적지 않고 '비슷해요'로 끝낸다")
    void trendInsight_flat() {
        assertThat(writer.trendInsight(trend(0, 0, 0, 0, 20_000, 20_000), 0))
                .isEqualTo("가장 식비가 많이 나온 달은 4월, 이번 달은 지난달과 비슷해요.");
    }

    @Test
    @DisplayName("지난달이 0원이라 증감률이 없으면 뒷절을 지어내지 않고 앞절만 남긴다")
    void trendInsight_noDiffRate() {
        assertThat(writer.trendInsight(trend(0, 0, 0, 0, 0, 20_000), null))
                .isEqualTo("가장 식비가 많이 나온 달은 5월이에요.");
    }

    /** 최고 지출 월이 동률이면 과거 달이 남는다 - 비교 부등호를 >=로 바꾸면 여기가 1월로 뒤집힌다. */
    @Test
    @DisplayName("최고 지출 월이 동률이면 더 과거인 달을 적는다")
    void trendInsight_peakTieKeepsEarlierMonth() {
        assertThat(writer.trendInsight(trend(50_000, 50_000, 0, 0, 0, 10_000), null))
                .isEqualTo("가장 식비가 많이 나온 달은 12월이에요.");
    }

    // ---------- fixtures ----------

    /**
     * 카테고리 8개를 전부 채워 금액 내림차순(동률은 enum 선언 순서)으로 정렬 - 서비스의 categoryBreakdown과 같은 모양이다.
     * 적지 않은 카테고리는 0원으로 들어간다. 문구가 .get(0), .get(1)을 그냥 꺼내 쓸 수 있는 근거가 이 고정 길이다.
     */
    /**
     * 빈도 축 경계만 보기 위한 재료. 금액 축 셋(감정 30% / 상위 2개 40% / 요일 15%)을 전부 기준 아래로
     * 평평하게 깔아 두어, 3번째 문장이 빈도로 가는지 아닌지가 오직 기간과 건수에만 달리게 만든다.
     */
    private static PeriodFacts flatFactsWithFrequency(LocalDate start, LocalDate end, int mostFrequentCount) {
        return new PeriodFacts(
                start, end, 10_000,
                categories(10_000, Map.of(
                        ExpenseCategory.DELIVERY, 2_000, ExpenseCategory.CONVENIENCE_STORE, 2_000,
                        ExpenseCategory.CAFE, 2_000, ExpenseCategory.GROCERY, 2_000,
                        ExpenseCategory.DESSERT, 2_000)),
                emotions(10_000, Map.of(
                        ExpenseEmotion.STRESS, 3_000, ExpenseEmotion.COMPENSATION, 3_000,
                        ExpenseEmotion.CONVENIENCE, 2_000, ExpenseEmotion.IMPULSE, 2_000)),
                weekdays(1_500, 1_500, 1_500, 1_500, 1_500, 1_500, 1_000),
                ExpenseEmotion.STRESS, ExpenseCategory.CONVENIENCE_STORE, mostFrequentCount,
                4_000, 6_000, ChallengeProgress.NONE);
    }

    private static List<CategoryAmount> categories(int totalAmount, Map<ExpenseCategory, Integer> amounts) {
        Comparator<CategoryAmount> byAmount = Comparator.comparingLong(CategoryAmount::amount);
        return Arrays.stream(ExpenseCategory.values())
                .map(category -> {
                    int amount = amounts.getOrDefault(category, 0);
                    return new CategoryAmount(category, amount, percent(amount, totalAmount));
                })
                .sorted(byAmount.reversed().thenComparing(CategoryAmount::category))
                .toList();
    }

    /** 이유 5개 전부, 같은 규칙. */
    private static List<EmotionAmount> emotions(int totalAmount, Map<ExpenseEmotion, Integer> amounts) {
        Comparator<EmotionAmount> byAmount = Comparator.comparingLong(EmotionAmount::amount);
        return Arrays.stream(ExpenseEmotion.values())
                .map(emotion -> {
                    int amount = amounts.getOrDefault(emotion, 0);
                    return new EmotionAmount(emotion, amount, percent(amount, totalAmount));
                })
                .sorted(byAmount.reversed().thenComparing(EmotionAmount::emotion))
                .toList();
    }

    /** 서비스의 percentOf와 같은 반올림 - 여기서 다르게 계산하면 테스트가 문구 대신 자기 산수를 검증하게 된다. */
    private static int percent(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }

    /** 일 ~ 토 순서(이 앱의 주는 일요일 시작)로 7개를 채운 요일 집계 - 서비스가 내려주는 모양 그대로다. */
    private static List<WeekdayAmount> weekdays(int sun, int mon, int tue, int wed, int thu, int fri, int sat) {
        int[] amounts = {sun, mon, tue, wed, thu, fri, sat};
        List<WeekdayAmount> breakdown = new ArrayList<>(amounts.length);
        for (int i = 0; i < amounts.length; i++) {
            breakdown.add(new WeekdayAmount(WeekdayAmount.DISPLAY_ORDER.get(i), amounts[i]));
        }
        return breakdown;
    }

    /** 2025-12부터 6개월, 오름차순(과거 -> 최근) - 마지막 원소가 선택한 달이다. */
    private static List<MonthlyAmount> trend(int... amounts) {
        YearMonth windowStart = YearMonth.of(2025, 12);
        List<MonthlyAmount> trend = new ArrayList<>(amounts.length);
        for (int i = 0; i < amounts.length; i++) {
            trend.add(new MonthlyAmount(windowStart.plusMonths(i), amounts[i]));
        }
        return trend;
    }
}
