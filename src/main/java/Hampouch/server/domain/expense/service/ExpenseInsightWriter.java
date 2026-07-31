package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.CategoryAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.EmotionAmount;
import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse.WeekdayAmount;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse.MonthlyAmount;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 인사이트 문구 3종(weekdayInsight / pouchInsight / trendInsight)을 만드는 자리.
 * 집계 결과를 보고 미리 정해 둔 문장을 고르는 규칙 기반 템플릿이다.
 * ExpenseAnalysisService에서 떼어낸 이유
 * 1. 집계는 숫자를 만들고 여기는 그 숫자를 한국어 문장으로 Paraphrasing.
 * 2. 이 클래스가 추후 확장 시 실제 LLM 호출로 바뀔 가능성이 가장 높은 자리라서, 지금 경계를 그어 두면
 * 그때 서비스 코드를 건드리지 않고 이 구현만 갈아끼울 수 있다.
 */
@Component
public class ExpenseInsightWriter {

    /**
     * 배지에 2위 요일까지 함께 적을 기준. 1, 2위가 엎치락뒤치락할 때 하나만 집으면 사실을 왜곡하게 된다.
     * 반대로 기준이 없으면 압도적인 1위가 있어도 2위가 따라붙어 배지가 흐려진다.
     */
    private static final double WEEKDAY_TIE_RATIO = 0.8;

    /**
     * pouchInsight 2번째 문장이 대부분 ~ 때문이었어요까지 붙일 최소 카테고리 비중(기간 총액 중 %).
     * 카테고리는 8개라 균등하면 12.5%다. 30%면 나머지 일곱을 합친 것과 견줄 만한 덩어리라
     * 이 카테고리가 가장 컸다라고 할 만 하다.
     * 미달이면 이유를 말하지 않고 상위 2개를 나열만
     */
    private static final int CATEGORY_FOCUS_PERCENT = 30;

    /** 3번째 문장에서 감정 축을 고를 최소 비중. 감정은 5개라 균등하면 20%다. */
    private static final int EMOTION_FOCUS_PERCENT = 40;

    /** 감정 축이 미달일 때 상위 2개 카테고리 합으로 갈아탈 기준(8개 중 2개가 균등하면 25%). */
    private static final int TOP_TWO_FOCUS_PERCENT = 60;

    /** 그마저 미달일 때 요일 쏠림으로 갈아탈 기준(7요일 균등이면 14%). */
    private static final int WEEKDAY_FOCUS_PERCENT = 25;

    /** 금액이 아니라 횟수로 말할 최소 건수. 금액 축이 셋 다 밋밋할 때만 쓰는 마지막 후보다. */
    private static final int FREQUENT_COUNT_THRESHOLD = 8;

    /** 전반/후반 비교로 마지막 문장을 만들 최소 기간. 이보다 짧으면 반으로 갈라도 표본이 안 된다. */
    private static final int CLOSING_MIN_DAYS = 7;

    private static final String NO_EXPENSE_WEEKDAY = "지출 기록이 없어 요일 분석을 제공하지 않아요!";
    private static final String NO_EXPENSE_POUCH = "지출 기록이 없어 햄포치 분석을 제공하지 않아요!";
    private static final String NO_EXPENSE_TREND = "지출 기록이 없어 햄포치 분석을 제공하지 않아요!";

    /** 어느 축도 기준을 넘지 못했을 때의 3번째 문장. 이 경우 이어질 제안(4번째 문장)도 없다. */
    private static final String NO_FOCUS_CLOSING = "특별히 튀는 항목 없이 고르게 쓰셨어요.";

    private static final String CLOSING_ON_TRACK = "지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!";
    private static final String CLOSING_NEEDS_GOAL = "무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?";

    /** 기간이 한 달을 통째로 덮지 않을 때의 1번째 문장 라벨. */
    private static final String CHALLENGE_PERIOD_LABEL = "이번 챌린지 기간";

    private static final char HANGUL_FIRST = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;
    private static final int HANGUL_FINAL_COUNT = 28;

    /**
     * 요일 한 글자 라벨. DayOfWeek.getDisplayName(SHORT, KOREAN)으로도 얻을 수 있지만
     * 그 값은 JDK가 들고 있는 CLDR 버전과 실행 환경 로케일에 딸려 있어 서버마다 달라질 여지가 있다.
     * 응답 본문에 그대로 나가는 문자열이라 여기서 못박는다.
     */
    private static final Map<DayOfWeek, String> WEEKDAY_LABEL = new EnumMap<>(Map.of(
            DayOfWeek.SUNDAY, "일",
            DayOfWeek.MONDAY, "월",
            DayOfWeek.TUESDAY, "화",
            DayOfWeek.WEDNESDAY, "수",
            DayOfWeek.THURSDAY, "목",
            DayOfWeek.FRIDAY, "금",
            DayOfWeek.SATURDAY, "토"
    ));

    /**
     * 카테고리별 제안 문장. 배달 문구가 시안 원문이고 나머지는 같은 어투로 맞춘 것이다.
     * 장보기만 줄이세요가 아닌 이유는 장보기 자체는 외식·배달을 대체하는 쪽이라
     * 줄이라고 하면 앱이 권하는 방향과 반대되는 조언이 되기 때문.
     * ETC는 사용자 정의 카테고리가 전부 접혀 들어오는 자리라 무엇을 줄이라고 특정할 수 없다.
     */
    private static final Map<ExpenseCategory, String> CATEGORY_ADVICE = new EnumMap<>(Map.of(
            ExpenseCategory.DELIVERY, "배달 식품을 줄여보는 건 어떨까요?",
            ExpenseCategory.DINING_OUT, "외식을 한 번만 줄여보는 건 어떨까요?",
            ExpenseCategory.CONVENIENCE_STORE, "편의점에 들르는 횟수를 줄여보는 건 어떨까요?",
            ExpenseCategory.CAFE, "카페 대신 집에서 한 잔 어떨까요?",
            ExpenseCategory.GROCERY, "장 볼 때 필요한 만큼만 담아보는 건 어떨까요?",
            ExpenseCategory.DESSERT, "간식을 조금만 줄여보는 건 어떨까요?",
            ExpenseCategory.DRINKING, "술자리를 한 번만 줄여보는 건 어떨까요?",
            ExpenseCategory.ETC, "어디에 돈이 나갔는지 한 번 살펴볼까요?"
    ));

    /**
     * 감정별 제안 문장. 카테고리 조언이 무엇을 줄일까라면 이쪽은 왜 쓰게 되는가를 건드린다 —
     * 같은 배달 지출이라도 스트레스와 귀찮음은 해법이 다르기 때문에 축을 나눠 둔다.
     */
    private static final Map<ExpenseEmotion, String> EMOTION_ADVICE = new EnumMap<>(Map.of(
            ExpenseEmotion.STRESS, "스트레스받는 날엔 먹는 것 말고 다른 보상도 하나 정해둘까요?",
            ExpenseEmotion.COMPENSATION, "보상은 좋지만, 주 1회로 정해두면 훨씬 가벼워져요.",
            ExpenseEmotion.CONVENIENCE, "간단히 데워 먹을 걸 미리 사두면 귀찮은 날이 줄어요.",
            ExpenseEmotion.IMPULSE, "먹고 싶을 때 10분만 미뤄보는 것도 방법이에요.",
            ExpenseEmotion.ETC, "적어둔 이유를 다시 보면 줄일 지점이 보일 거예요."
    ));

    // 제네릭 추론이 꼬이지 않도록 comparingInt를 먼저 변수에 담고 reversed()를 건다(서비스의 정렬과 같은 방식).
    private static final Comparator<WeekdayAmount> BY_WEEKDAY_AMOUNT = Comparator.comparingInt(WeekdayAmount::amount);

    /** 금액 내림차순, 동률이면 화면 표시 순서(일~토) — tie-breaker가 없으면 0원 요일들의 순위가 매번 달라진다. */
    private static final Comparator<WeekdayAmount> WEEKDAY_RANK = BY_WEEKDAY_AMOUNT.reversed()
            .thenComparingInt(weekday -> WeekdayAmount.DISPLAY_ORDER.indexOf(weekday.dayOfWeek()));

    /**
     * pouchInsight가 문장을 고르는 데 필요한 기간 단위 재료 묶음.
     *
     * 세 breakdown은 서비스가 응답에 실어 보내는 것과 같은 객체다 — 문구와 화면 숫자가
     * 다른 집계를 보면 70%라고 적힌 옆에 다른 숫자가 뜨는 사고가 난다.
     *
     * topCategoryEmotion만 여기서 새로 계산해 넘긴다. 기간 전체 감정 1위가 아니라
     * 지출 1위 카테고리 안에서의 감정 1위여야 2번째 문장이 실제로 참이 되기 때문.
     *
     * 전반/후반 금액은 마지막 문장(유지 vs 조정)의 유일한 근거다. 챌린지 예산·한도를
     * 보지 않고 기간 안에서만 판단하기 위한 대용치다 — 한계는 closingSentence 주석 참조.
     *
     * 지출이 하나도 없는 기간이면 이 묶음 자체가 null로 넘어온다.
     */
    public record PeriodFacts(
            LocalDate periodStart,
            LocalDate periodEnd,
            int totalAmount,
            List<CategoryAmount> categoryBreakdown,
            List<EmotionAmount> emotionBreakdown,
            List<WeekdayAmount> weekdayBreakdown,
            ExpenseEmotion topCategoryEmotion,
            ExpenseCategory mostFrequentCategory,
            int mostFrequentCount,
            int firstHalfAmount,
            int secondHalfAmount
    ) {}

    /** 3번째 문장과 거기서 이어지는 4번째 제안 문장. 쏠린 축이 없으면 advice가 null이다. */
    private record Highlight(String sentence, String advice) {}

    /**
     * 요일 차트 위 배지. 시안: 금, 토 지출이 가장 많아요.
     * 2위가 1위의 WEEKDAY_TIE_RATIO 이상이면 둘을 함께 적고, 그때 나열 순서는
     * 금액순이 아니라 화면 표시 순서다(시안이 토 > 금인데도 금, 토로 적혀 있다 — 차트와 눈이 같이 움직이게).
     */
    public String weekdayInsight(List<WeekdayAmount> weekdayBreakdown, int totalAmount) {
        if (totalAmount == 0) {
            return NO_EXPENSE_WEEKDAY;
        }

        // weekdayBreakdown은 0원 요일까지 채운 7개 고정이라 1, 2위가 항상 존재한다.
        List<WeekdayAmount> ranked = weekdayBreakdown.stream().sorted(WEEKDAY_RANK).toList();
        WeekdayAmount first = ranked.get(0);
        WeekdayAmount second = ranked.get(1);

        if (second.amount() == 0 || second.amount() < first.amount() * WEEKDAY_TIE_RATIO) {
            // 하나만 적을 땐 한 글자 라벨이 아니라 토요일로 풀어 쓴다 — 한 글자는 나열할 때만 자연스럽다.
            return "%s요일 지출이 가장 많아요".formatted(WEEKDAY_LABEL.get(first.dayOfWeek()));
        }

        // 타입 인자를 명시하는 건 취향이 아니라 필요 - indexOf(Object)라 추론이 Object로 떨어질 수 있다
        String joined = Stream.of(first.dayOfWeek(), second.dayOfWeek())
                .sorted(Comparator.<DayOfWeek>comparingInt(WeekdayAmount.DISPLAY_ORDER::indexOf))
                .map(WEEKDAY_LABEL::get)
                .collect(Collectors.joining(", "));
        return "%s 지출이 가장 많아요".formatted(joined);
    }

    /**
     * 하단 햄포치의 식비 분석 카드. 최대 5문장을 이어 붙인다.
     *
     * 1. 기간과 총액 — 나머지 문장이 말하는 %의 분모를 먼저 깔아준다.
     * 2. 지출 1위 카테고리와 그 안의 이유(비중이 CATEGORY_FOCUS_PERCENT 이상일 때만 이유까지).
     * 3. 가장 인상적인 수치 — 감정, 상위 2개 카테고리, 요일, 횟수 순으로 먼저 걸리는 축 하나.
     * 4. 그 축에 이어지는 제안. 3번이 고르게 썼다로 끝나면 이 문장은 없다.
     * 5. 기간 후반부 흐름에 따른 유지/조정 권유.
     *
     * 3번이 카테고리가 아니라 감정을 먼저 보는 이유는 2번이 이미 카테고리를 지목했기 때문이다 —
     * 같은 축을 두 번 말하면 문단이 제자리를 맴돈다.
     *
     * 제약: 여기 조건에 챌린지 예산/한도/성공일수를 넣지 말 것. 넣는 순간 기간만으로 계산이
     * 불가능해져 이 API가 challengeId를 받아야 하고, 단일 엔드포인트 구조가 무너진다.
     * 온보딩의 약한 카테고리도 같은 이유로 여기서 읽지 않는다 — 그 신호는 홈의
     * WEAK_CATEGORY_ALERT 카드(#52) 몫이고, 진행 중 챌린지 조회는 만료 lazy 확정을 건드린다.
     */
    public String pouchInsight(PeriodFacts facts) {
        if (facts == null || facts.totalAmount() == 0) {
            return NO_EXPENSE_POUCH;
        }

        List<String> sentences = new ArrayList<>(5);
        sentences.add(periodTotalSentence(facts));
        sentences.add(topCategorySentence(facts));

        Highlight highlight = pickHighlight(facts);
        sentences.add(highlight.sentence());
        if (highlight.advice() != null) {
            sentences.add(highlight.advice());
        }

        String closing = closingSentence(facts);
        if (closing != null) {
            sentences.add(closing);
        }
        return String.join(" ", sentences);
    }

    /**
     * 추이 화면 하단 문구. 시안: 가장 식비가 많이 나온 달은 12월, 이번 달은 지난달에 비해 6% 증가했어요.
     *
     * 12월처럼 연도 없이 적어도 되는 이유는 창이 6개월 고정이라 같은 월 숫자가 두 번 나올 수 없기 때문이다.
     * 창 길이를 늘리면 이 표기부터 깨진다.
     *
     * diffRateFromLastMonth가 null인 경우(지난달 0원)는 증감을 말할 수 없으므로 앞 절만 남긴다.
     */
    public String trendInsight(List<MonthlyAmount> trend, Integer diffRateFromLastMonth) {
        MonthlyAmount peak = null;
        for (MonthlyAmount monthly : trend) {
            // 동률이면 먼저 나온(과거) 달을 남긴다 — 부등호를 >=로 바꾸면 최근 달이 이겨서 문구가 뒤집힌다.
            if (peak == null || monthly.amount() > peak.amount()) {
                peak = monthly;
            }
        }
        if (peak == null || peak.amount() == 0) {
            return NO_EXPENSE_TREND;
        }

        YearMonth peakMonth = peak.month();
        String head = "가장 식비가 많이 나온 달은 %d월".formatted(peakMonth.getMonthValue());

        if (diffRateFromLastMonth == null) {
            return head + "이에요.";
        }
        if (diffRateFromLastMonth > 0) {
            return head + ", 이번 달은 지난달에 비해 %d%% 증가했어요.".formatted(diffRateFromLastMonth);
        }
        if (diffRateFromLastMonth < 0) {
            // 화면엔 부호 없는 감소율을 적는다 - 마이너스 부호와 줄었다는 말이 겹쳐 이중 부정으로 읽힌다.
            return head + ", 이번 달은 지난달에 비해 %d%% 줄었어요.".formatted(-diffRateFromLastMonth);
        }
        return head + ", 이번 달은 지난달과 비슷해요.";
    }

    /**
     * 기간을 반으로 가르는 지점(전반부의 마지막 날). 홀수 일수면 전반부가 하루 짧다.
     * public인 이유는 서비스가 같은 규칙으로 금액을 갈라 담아야 하기 때문 —
     * 규칙이 두 곳에 복사되면 경계가 하루 어긋나도 아무도 모른다.
     */
    public static LocalDate firstHalfEnd(LocalDate periodStart, LocalDate periodEnd) {
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        return periodStart.plusDays(days / 2 - 1);
    }

    /** 1번째 문장. 기간이 한 달을 통째로 덮으면 5월, 아니면 챌린지 기간으로 부른다. */
    private static String periodTotalSentence(PeriodFacts facts) {
        return "%s 식비는 %s원이에요.".formatted(
                periodLabel(facts.periodStart(), facts.periodEnd()), formatAmount(facts.totalAmount()));
    }

    /**
     * 달력에서 온 조회는 항상 1일~말일이고 챌린지에서 온 조회는 그렇지 않다는 점만으로 구분한다.
     * 화면 이름을 파라미터로 받지 않기 위한 판정이라 완벽하진 않다 —
     * 챌린지 기간이 우연히 1일~말일이면 5월로 불린다(문장이 틀리지는 않는다).
     */
    private static String periodLabel(LocalDate periodStart, LocalDate periodEnd) {
        YearMonth month = YearMonth.from(periodStart);
        if (periodStart.getDayOfMonth() == 1 && periodEnd.equals(month.atEndOfMonth())) {
            return "%d월".formatted(month.getMonthValue());
        }
        return CHALLENGE_PERIOD_LABEL;
    }

    /**
     * 2번째 문장. 1위 비중이 CATEGORY_FOCUS_PERCENT 이상이면 이유까지 붙이고,
     * 미만이면 상위 2개를 나열만 한다(쏠린 곳이 없는데 이유를 말하면 근거 없는 단정이 된다).
     * categoryBreakdown은 0원 카테고리까지 채운 8개 고정이라 인덱스 0, 1은 항상 존재한다.
     */
    private static String topCategorySentence(PeriodFacts facts) {
        CategoryAmount first = facts.categoryBreakdown().get(0);
        String firstLabel = mentionLabel(first.category());

        if (first.ratio() >= CATEGORY_FOCUS_PERCENT && facts.topCategoryEmotion() != null) {
            return "그 중 %s %d%%로 가장 컸고, 대부분 '%s' 때문이었어요."
                    .formatted(withJosa(firstLabel, "이", "가"), first.ratio(), reasonLabel(facts.topCategoryEmotion()));
        }

        CategoryAmount second = facts.categoryBreakdown().get(1);
        if (second.amount() == 0) {
            return "그 중 %s에 지출이 몰려 있었어요.".formatted(firstLabel);
        }
        return "그 중 %s, %s 가장 많은 비중을 차지했어요."
                .formatted(firstLabel, withJosa(mentionLabel(second.category()), "이", "가"));
    }

    /**
     * 3번째 문장과 4번째 제안. 축을 하나만 고르는 이유는 카드가 나열이 아니라 말이 되어야 하기 때문이고,
     * 감정을 맨 앞에 둔 이유는 2번째 문장이 이미 카테고리를 지목했기 때문이다.
     * 넷 다 기준 미달이면 고르게 썼다는 문장으로 닫고 제안을 만들지 않는다.
     */
    private static Highlight pickHighlight(PeriodFacts facts) {
        EmotionAmount topEmotion = facts.emotionBreakdown().get(0);
        if (topEmotion.ratio() >= EMOTION_FOCUS_PERCENT) {
            return new Highlight(
                    "'%s' 때문에 쓴 돈이 전체의 %d%%나 돼요.".formatted(reasonLabel(topEmotion.emotion()), topEmotion.ratio()),
                    EMOTION_ADVICE.get(topEmotion.emotion()));
        }

        CategoryAmount first = facts.categoryBreakdown().get(0);
        CategoryAmount second = facts.categoryBreakdown().get(1);
        int topTwoRatio = first.ratio() + second.ratio();
        if (second.amount() > 0 && topTwoRatio >= TOP_TWO_FOCUS_PERCENT) {
            return new Highlight(
                    "%s %s 두 곳이 전체의 %d%%를 차지했어요.".formatted(
                            withJosa(mentionLabel(first.category()), "과", "와"),
                            mentionLabel(second.category()), topTwoRatio),
                    CATEGORY_ADVICE.get(first.category()));
        }

        WeekdayAmount topWeekday = facts.weekdayBreakdown().stream().sorted(WEEKDAY_RANK).toList().get(0);
        int weekdayRatio = shareOf(topWeekday.amount(), facts.totalAmount());
        if (weekdayRatio >= WEEKDAY_FOCUS_PERCENT) {
            String label = WEEKDAY_LABEL.get(topWeekday.dayOfWeek());
            return new Highlight(
                    "%s요일에만 전체의 %d%%가 몰렸어요.".formatted(label, weekdayRatio),
                    "%s요일엔 미리 한 끼를 정해두면 지출이 덜 흔들려요.".formatted(label));
        }

        if (facts.mostFrequentCategory() != null && facts.mostFrequentCount() >= FREQUENT_COUNT_THRESHOLD) {
            String label = mentionLabel(facts.mostFrequentCategory());
            return new Highlight(
                    "%s %d번으로 가장 자주 기록됐어요.".formatted(withJosa(label, "이", "가"), facts.mostFrequentCount()),
                    "한 번 쓰는 금액은 작아도 횟수가 쌓이면 커져요. %s 횟수부터 줄여볼까요?".formatted(label));
        }

        return new Highlight(NO_FOCUS_CLOSING, null);
    }

    /**
     * 5번째 문장. 기간을 반으로 갈라 후반부 일평균이 전반부보다 낮으면 유지, 아니면 목표 재설정을 권한다.
     *
     * TODO(#38 후속): Challenge 연계 방안 검토. 비용을 잘 지키고 있다고 말할 진짜 기준은
     * 챌린지 예산·일 한도 대비 사용률인데, 지금은 그 값을 못 본다(analysis API가 challengeId를
     * 받지 않고, 진행 중 챌린지 조회는 만료 lazy 확정이라 GET에서 건드리면 포기 API의 409 경계가 흔들린다).
     * 그래서 기간 내부 추세라는 대용치를 쓴다 — 전체 금액이 얼마든 후반부가 줄기만 하면 유지 쪽 문장이
     * 나온다는 한계가 있다. 챌린지 쪽에서 안전한 읽기 경로가 생기면 이 판정부터 갈아끼울 것.
     *
     * 기간이 CLOSING_MIN_DAYS 미만이면 문장 자체를 만들지 않는다(null 반환).
     */
    private static String closingSentence(PeriodFacts facts) {
        long totalDays = ChronoUnit.DAYS.between(facts.periodStart(), facts.periodEnd()) + 1;
        if (totalDays < CLOSING_MIN_DAYS) {
            return null;
        }

        LocalDate firstHalfEnd = firstHalfEnd(facts.periodStart(), facts.periodEnd());
        long firstHalfDays = ChronoUnit.DAYS.between(facts.periodStart(), firstHalfEnd) + 1;
        long secondHalfDays = totalDays - firstHalfDays;
        if (firstHalfDays <= 0 || secondHalfDays <= 0) {
            return null;
        }

        double firstHalfDaily = facts.firstHalfAmount() / (double) firstHalfDays;
        double secondHalfDaily = facts.secondHalfAmount() / (double) secondHalfDays;
        return secondHalfDaily < firstHalfDaily ? CLOSING_ON_TRACK : CLOSING_NEEDS_GOAL;
    }

    /**
     * ETC만 라벨(직접 입력)을 그대로 못 쓴다 — 직접 입력이 가장 컸고 식으로는 문장이 되지 않고,
     * 사용자가 만든 카테고리들이 전부 이 한 칸에 접혀 들어와 이름을 특정할 수도 없다.
     */
    private static String mentionLabel(ExpenseCategory category) {
        return category == ExpenseCategory.ETC ? "직접 입력한 항목" : category.getLabel();
    }

    /** 감정도 ETC만 라벨을 그대로 쓸 수 없다. 나머지는 따옴표로 감싸 문장에 넣는다. */
    private static String reasonLabel(ExpenseEmotion emotion) {
        return emotion == ExpenseEmotion.ETC ? "직접 입력한 이유" : emotion.getLabel();
    }

    /**
     * 조사 자동 선택. 카테고리 라벨이 배달(받침 있음)과 카페(없음)처럼 섞여 있어
     * 배달가 가장 컸고 같은 문장이 나오는 걸 막는다.
     *
     * 감정 라벨에는 쓰지 않는다 — 귀찮아서, 그냥 먹고 싶어서는 명사가 아니라 절이라
     * 조사를 맞춰도 문장이 안 된다. 그쪽은 따옴표로 감싸 인용하는 방식으로 처리한다(reasonLabel).
     */
    private static String withJosa(String word, String withFinalConsonant, String withoutFinalConsonant) {
        return word + (hasFinalConsonant(word) ? withFinalConsonant : withoutFinalConsonant);
    }

    /**
     * 마지막 글자에 받침이 있는지. 한글 음절은 0xAC00부터 (초성, 중성, 종성) 순서로 배열돼 있어
     * 28(종성 개수, 없음 포함)로 나눈 나머지가 0이면 받침이 없다.
     * 한글이 아닌 글자로 끝나면 받침이 있는 쪽을 고른다 — 카페(2호점)이 처럼 붙는 편이 덜 어색하기 때문.
     */
    private static boolean hasFinalConsonant(String word) {
        if (word.isEmpty()) {
            return true;
        }
        char last = word.charAt(word.length() - 1);
        if (last < HANGUL_FIRST || last > HANGUL_LAST) {
            return true;
        }
        return (last - HANGUL_FIRST) % HANGUL_FINAL_COUNT != 0;
    }

    /** 천 단위 구분자. 로케일을 고정하는 이유는 응답 본문에 그대로 나가는 문자열이라서. */
    private static String formatAmount(int amount) {
        return String.format(Locale.KOREA, "%,d", amount);
    }

    /** 기간 총액 중 한 항목이 차지하는 비중(정수 %). 분모는 항상 기간 총액이다. */
    private static int shareOf(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }
}
