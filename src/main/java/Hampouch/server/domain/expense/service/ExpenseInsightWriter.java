package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.service.ChallengeProgress;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 인사이트 문구 3종(weekdayInsight / pouchInsight / trendInsight)을 만드는 규칙 기반 템플릿.
 * 집계는 서비스가 하고 여기는 그 숫자를 한국어 문장으로 옮기기만 한다 —
 * 나중에 LLM 호출로 갈아끼울 자리라 경계를 서비스 밖에 둔다.
 */
@Component
public class ExpenseInsightWriter {

    /** 배지에 2위 요일까지 적을 기준. 1위가 압도적이면 혼자, 엎치락뒤치락하면 둘 다 적는다. */
    private static final double WEEKDAY_TIE_RATIO = 0.8;

    /** 2번째 문장에 이유까지 붙일 최소 1위 카테고리 비중. 미달이면 상위 2개를 나열만 한다. */
    private static final int CATEGORY_FOCUS_PERCENT = 30;

    /** 3번째 문장에서 감정 축을 고를 최소 비중. 감정은 5개라 균등하면 20%다. */
    private static final int EMOTION_FOCUS_PERCENT = 40;

    /** 감정 축이 미달일 때 상위 2개 카테고리 합으로 갈아탈 기준(8개 중 2개가 균등하면 25%). */
    private static final int TOP_TWO_FOCUS_PERCENT = 50;

    /** 그마저 미달일 때 요일 쏠림으로 갈아탈 기준(7요일 균등이면 14%). */
    private static final int WEEKDAY_FOCUS_PERCENT = 25;

    /**
     * 횟수로 말할 최소 건수를 기간에서 뽑아낼 제수. 고정 건수로 두면 7일 조회에서는 거의 안 걸리고
     * 한 달 조회에서는 너무 쉽게 걸려 기준 구실을 못 한다.
     */
    private static final int FREQUENT_COUNT_DIVISOR = 3;

    /** 기간이 짧아도 이 건수 아래로는 자주라고 말하지 않는다(기간 ÷ 제수가 1~2회까지 내려가는 것을 막는 하한). */
    private static final int FREQUENT_COUNT_MIN = 3;

    /** 빈도 축을 아예 보지 않을 기간. 이보다 짧으면 몇 번을 기록했든 습관이라 부를 표본이 아니다. */
    private static final int FREQUENT_MIN_DAYS = 7;

    /** 전반/후반 비교로 마지막 문장을 만들 최소 기간. 이보다 짧으면 반으로 갈라도 표본이 안 된다. */
    private static final int CLOSING_MIN_DAYS = 7;

    private static final String NO_EXPENSE_WEEKDAY = "지출 기록이 없어 요일 분석을 제공하지 않아요!";
    private static final String NO_EXPENSE_POUCH = "지출 기록이 없어 햄포치 분석을 제공하지 않아요!";
    private static final String NO_EXPENSE_TREND = "지출 기록이 없어 햄포치 분석을 제공하지 않아요!";

    /** 어느 축도 기준을 넘지 못했을 때의 3번째 문장. 이 경우 이어질 제안(4번째 문장)도 없다. */
    private static final String NO_FOCUS_CLOSING = "특별히 튀는 항목 없이 고르게 쓰셨어요.";

    private static final String CLOSING_ON_TRACK = "지금 흐름 그대로면 충분해요. 다음엔 조금만 더 낮춰 잡아도 되겠어요!";
    private static final String CLOSING_NEEDS_GOAL = "무리한 목표보다, 지킬 수 있는 선부터 정해볼까요?";

    /** 잘 지키고 있는 진행 중 챌린지가 조회 기간에 걸쳐 있을 때의 마지막 문장. 숫자를 말하지 않으므로 표본 크기와 무관하다. */
    private static final String CLOSING_NEXT_CHALLENGE = "다음 챌린지에서 식비를 살짝만 줄여보는 것도 추천해요.";

    /** 같은 자리인데 예산보다 앞서 쓰고 있을 때. 다음이 아니라 지금 남은 기간을 말한다. */
    private static final String CLOSING_CHALLENGE_OVER_PACE =
            "이번 챌린지는 예산보다 조금 빠르게 쓰고 있어요. 남은 기간엔 하루 한 끼만 줄여볼까요?";

    /** 기간이 한 달을 통째로 덮지 않을 때의 1번째 문장 라벨. */
    private static final String CHALLENGE_PERIOD_LABEL = "이번 챌린지 기간";

    private static final char HANGUL_FIRST = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;
    private static final int HANGUL_FINAL_COUNT = 28;

    /**
     * 요일 한 글자 라벨. getDisplayName은 JDK의 CLDR 버전과 로케일에 딸려 있어 서버마다 달라질 수 있어,
     * 응답에 그대로 나가는 문자열은 여기서 못박는다.
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
     * 카테고리별 제안 문장. 장보기만 줄이라고 하지 않는 것은 그 자체가 외식·배달을 대체하는 쪽이라서고,
     * ETC는 사용자 정의 카테고리가 전부 접혀 들어와 무엇을 줄이라고 특정할 수 없어서다.
     */
    private static final Map<ExpenseCategory, String> CATEGORY_ADVICE = new EnumMap<>(Map.of(
            ExpenseCategory.DELIVERY, "배달 음식을 줄여보는 건 어떨까요?",
            ExpenseCategory.DINING_OUT, "외식을 한 번만 줄여보는 건 어떨까요?",
            ExpenseCategory.CONVENIENCE_STORE, "편의점에 들르는 횟수를 줄여보는 건 어떨까요?",
            ExpenseCategory.CAFE, "카페 대신 집에서 한 잔 어떨까요?",
            ExpenseCategory.GROCERY, "장 볼 때 필요한 만큼만 담아보는 건 어떨까요?",
            ExpenseCategory.DESSERT, "간식을 조금만 줄여보는 건 어떨까요?",
            ExpenseCategory.DRINKING, "술자리를 한 번만 줄여보는 건 어떨까요?",
            ExpenseCategory.ETC, "어디에 돈이 나갔는지 한 번 살펴볼까요?"
    ));

    /** 감정별 제안 문장. 같은 배달 지출이라도 스트레스와 귀찮음은 해법이 달라 카테고리와 축을 나눠 둔다. */
    private static final Map<ExpenseEmotion, String> EMOTION_ADVICE = new EnumMap<>(Map.of(
            ExpenseEmotion.STRESS, "먹는 것 말고 다른 스트레스 해소법을 정해볼까요?",
            ExpenseEmotion.COMPENSATION, "보상은 좋지만, 주 1회로 정해두는 게 좋을 것 같아요.",
            ExpenseEmotion.CONVENIENCE, "간단히 데워 먹을 걸 미리 사두면 귀찮은 날이 줄어요.",
            ExpenseEmotion.IMPULSE, "먹고 싶을 때 10분만 미뤄보는 것도 방법이에요.",
            ExpenseEmotion.ETC, "적어둔 이유를 다시 보면 줄일 지점이 보일 거예요."
    ));

    // 제네릭 추론이 꼬이지 않도록 comparingLong을 먼저 담고 reversed()를 건다.
    private static final Comparator<WeekdayAmount> BY_WEEKDAY_AMOUNT = Comparator.comparingLong(WeekdayAmount::amount);

    /** 금액 내림차순, 동률이면 화면 표시 순서(일~토) — tie-breaker가 없으면 0원 요일들의 순위가 매번 달라진다. */
    private static final Comparator<WeekdayAmount> WEEKDAY_RANK = BY_WEEKDAY_AMOUNT.reversed()
            .thenComparingInt(weekday -> WeekdayAmount.DISPLAY_ORDER.indexOf(weekday.dayOfWeek()));

    /**
     * pouchInsight가 문장을 고를 때 보는 사실들. 세 breakdown은 응답에 실려 나가는 것과 같은 객체다.
     * topCategoryEmotion은 전체 1위 감정이 아니라 지출 1위 카테고리 안에서의 1위다.
     */
    public record PeriodFacts(
            LocalDate periodStart,
            LocalDate periodEnd,
            long totalAmount,
            List<CategoryAmount> categoryBreakdown,
            List<EmotionAmount> emotionBreakdown,
            List<WeekdayAmount> weekdayBreakdown,
            ExpenseEmotion topCategoryEmotion,
            ExpenseCategory mostFrequentCategory,
            int mostFrequentCount,
            long firstHalfAmount,
            long secondHalfAmount,
            /* 조회 기간과 겹치는 IN_PROGRESS 챌린지의 상태(없음 / 페이스 지킴 / 페이스 초과). 마지막 문장에서만 쓴다. */
            ChallengeProgress challengeProgress
    ) {}

    /** 3번째 문장과 거기서 이어지는 4번째 제안 문장. 쏠린 축이 없으면 advice가 null이다. */
    private record Highlight(String sentence, String advice) {}

    /** 요일 차트 위 배지. 2위가 1위의 WEEKDAY_TIE_RATIO 이상이면 둘을 화면 표시 순서로 함께 적는다. */
    public String weekdayInsight(List<WeekdayAmount> weekdayBreakdown, long totalAmount) {
        if (totalAmount == 0) {
            return NO_EXPENSE_WEEKDAY;
        }

        // weekdayBreakdown은 0원 요일까지 채운 7개 고정이라 1, 2위가 항상 존재한다.
        List<WeekdayAmount> ranked = weekdayBreakdown.stream().sorted(WEEKDAY_RANK).toList();
        WeekdayAmount first = ranked.get(0);
        WeekdayAmount second = ranked.get(1);

        if (second.amount() == 0 || second.amount() < first.amount() * WEEKDAY_TIE_RATIO) {
            // 하나만 적을 땐 한 글자 라벨이 아니라 %요일로 풀어 쓴다
            return "%s요일 지출이 가장 많아요".formatted(WEEKDAY_LABEL.get(first.dayOfWeek()));
        }

        // 나열 순서는 금액이 아니라 화면의 요일 순서를 따른다 — 배지와 차트가 같이 읽혀야 한다.
        String joined = Stream.of(first.dayOfWeek(), second.dayOfWeek())
                .sorted(Comparator.comparingInt(WeekdayAmount.DISPLAY_ORDER::indexOf))
                .map(WEEKDAY_LABEL::get)
                .collect(Collectors.joining(", "));
        return "%s 지출이 가장 많아요".formatted(joined);
    }

    /**
     * 햄포치의 식비 분석 카드. 기간·총액 / 1위 카테고리 / 쏠린 축 하나 / 그 축의 제안 / 마무리 순으로
     * 최대 5문장을 이어 붙인다(3번이 고르게 썼다로 끝나면 4번은 없다).
     * 챌린지 예산·한도·성공일수를 조건에 넣지 말 것 — 기간만으로 계산할 수 없게 되어 challengeId가 필요해진다.
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
     * 추이 화면 하단 문구. 연도 없이 12월로 적는 것은 창이 6개월 고정이라 같은 월이 두 번 나올 수 없어서고,
     * 창을 늘리면 이 표기부터 깨진다. diffRateFromLastMonth가 null이면 증감 절을 붙이지 않는다.
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

    /** 기간을 반으로 가르는 지점. 전반/후반 일평균 비교의 경계다. */
    public static LocalDate firstHalfEnd(LocalDate periodStart, LocalDate periodEnd) {
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        return periodStart.plusDays(days / 2 - 1);
    }

    /** 1번째 문장. 나머지 문장이 말하는 %의 분모를 먼저 깔아 준다. */
    private static String periodTotalSentence(PeriodFacts facts) {
        return "%s 식비는 %s원이에요.".formatted(
                periodLabel(facts.periodStart(), facts.periodEnd()), formatAmount(facts.totalAmount()));
    }

    /**
     * 기간이 1일~말일이면 5월, 아니면 이번 챌린지 기간. 화면 이름을 받지 않으려는 판정이라
     * 챌린지가 우연히 1일~말일이면 5월로 불리지만 문장이 틀리지는 않는다.
     */
    private static String periodLabel(LocalDate periodStart, LocalDate periodEnd) {
        YearMonth month = YearMonth.from(periodStart);
        if (periodStart.getDayOfMonth() == 1 && periodEnd.equals(month.atEndOfMonth())) {
            return "%d월".formatted(month.getMonthValue());
        }
        return CHALLENGE_PERIOD_LABEL;
    }

    /**
     * 2번째 문장. 1위 비중이 기준 미달이면 이유 없이 상위 2개를 나열만 한다 —
     * 쏠린 곳이 없는데 이유를 말하면 근거 없는 단정이 된다.
     * categoryBreakdown은 0원까지 채운 8개 고정이라 인덱스 0, 1은 항상 있다.
     */
    private static String topCategorySentence(PeriodFacts facts) {
        CategoryAmount first = facts.categoryBreakdown().getFirst();
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
     * 3번째 문장과 4번째 제안. 카드가 나열이 아니라 말이 되도록 축은 하나만 고르고,
     * 2번째 문장이 이미 카테고리를 지목했으므로 감정을 맨 앞에 둔다.
     * 넷 다 미달이면 고르게 썼다는 문장으로 닫고 제안을 만들지 않는다.
     */
    private static Highlight pickHighlight(PeriodFacts facts) {
        EmotionAmount topEmotion = facts.emotionBreakdown().getFirst();
        if (topEmotion.ratio() >= EMOTION_FOCUS_PERCENT) {
            return new Highlight(
                    "'%s' 때문에 쓴 돈이 전체의 %d%%나 돼요.".formatted(reasonLabel(topEmotion.emotion()), topEmotion.ratio()),
                    EMOTION_ADVICE.get(topEmotion.emotion()));
        }

        CategoryAmount first = facts.categoryBreakdown().get(0);
        CategoryAmount second = facts.categoryBreakdown().get(1);
        // 2위가 0원 = 카테고리가 하나뿐. 두 곳 문장이 성립하지 않으므로 한 곳만 지목한다
        // (이 경우 first.ratio()는 정의상 100이라 기준 비교도 필요 없다).
        if (second.amount() == 0) {
            return new Highlight(
                    "%s 한 곳에서만 전체의 %d%%를 썼어요.".formatted(mentionLabel(first.category()), first.ratio()),
                    CATEGORY_ADVICE.get(first.category()));
        }

        int topTwoRatio = first.ratio() + second.ratio();
        if (topTwoRatio >= TOP_TWO_FOCUS_PERCENT) {
            return new Highlight(
                    "%s %s 두 곳이 전체의 %d%%를 차지했어요.".formatted(
                            withJosa(mentionLabel(first.category()), "과", "와"),
                            mentionLabel(second.category()), topTwoRatio),
                    CATEGORY_ADVICE.get(first.category()));
        }

        WeekdayAmount topWeekday = facts.weekdayBreakdown().stream().sorted(WEEKDAY_RANK).toList().getFirst();
        int weekdayRatio = shareOf(topWeekday.amount(), facts.totalAmount());
        if (weekdayRatio >= WEEKDAY_FOCUS_PERCENT) {
            String label = WEEKDAY_LABEL.get(topWeekday.dayOfWeek());
            return new Highlight(
                    "%s요일에만 전체의 %d%%가 몰렸어요.".formatted(label, weekdayRatio),
                    "%s요일엔 미리 한 끼를 정해두면 지출이 덜 흔들려요.".formatted(label));
        }

        long totalDays = totalDays(facts);
        if (totalDays >= FREQUENT_MIN_DAYS
                && facts.mostFrequentCategory() != null
                && facts.mostFrequentCount() >= frequentCountThreshold(totalDays)) {
            String label = mentionLabel(facts.mostFrequentCategory());
            return new Highlight(
                    "%s %d번으로 가장 자주 기록됐어요.".formatted(withJosa(label, "이", "가"), facts.mostFrequentCount()),
                    "한 번 쓰는 금액은 작아도 횟수가 쌓이면 커져요. %s 횟수부터 줄여볼까요?".formatted(label));
        }

        return new Highlight(NO_FOCUS_CLOSING, null);
    }

    /** 조회 기간의 일수. 양 끝을 모두 포함하므로 하루짜리 조회도 0이 아니라 1이다. */
    private static long totalDays(PeriodFacts facts) {
        return ChronoUnit.DAYS.between(facts.periodStart(), facts.periodEnd()) + 1;
    }

    /**
     * 빈도 축이 걸릴 최소 건수. 기간이 길수록 같은 건수의 의미가 옅어지므로 기간에 비례시킨다.
     * 내림이라 7일 → 3회(하한), 14일 → 4회, 31일 → 10회.
     */
    private static int frequentCountThreshold(long totalDays) {
        return (int) Math.max(FREQUENT_COUNT_MIN, totalDays / FREQUENT_COUNT_DIVISOR);
    }

    /**
     * 5번째 문장. 챌린지가 걸쳐 있으면 그 상태로 말하고, 없을 때만 기간 내부 추세로 말한다 —
     * 이미 목표를 잡아 둔 사람에게 추세로 목표를 다시 잡으라는 건 그 목표를 못 본 조언이다.
     * 챌린지 문구는 숫자를 말하지 않아 CLOSING_MIN_DAYS(표본 하한)를 보지 않는다.
     */
    private static String closingSentence(PeriodFacts facts) {
        return switch (facts.challengeProgress()) {
            case ON_TRACK -> CLOSING_NEXT_CHALLENGE;
            case OVER_PACE -> CLOSING_CHALLENGE_OVER_PACE;
            case NONE -> trendClosingSentence(facts);
        };
    }

    /**
     * 챌린지가 없을 때의 5번째 문장. 기간을 반으로 갈라 후반부 일평균이 전반부보다 낮으면 유지, 아니면 목표 재설정을 권한다.
     * 기간이 CLOSING_MIN_DAYS 미만이면 반으로 갈라도 표본이 안 되므로 문장 자체를 만들지 않는다.
     */
    private static String trendClosingSentence(PeriodFacts facts) {
        long totalDays = totalDays(facts);
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

    /** ETC만 라벨을 그대로 못 쓴다 — 직접 입력이 가장 컸고로는 문장이 되지 않는다. */
    private static String mentionLabel(ExpenseCategory category) {
        return category == ExpenseCategory.ETC ? "직접 입력한 항목" : category.getLabel();
    }

    /** 감정도 ETC만 라벨을 그대로 쓸 수 없다. 나머지는 따옴표로 감싸 문장에 넣는다. */
    private static String reasonLabel(ExpenseEmotion emotion) {
        return emotion == ExpenseEmotion.ETC ? "직접 입력한 이유" : emotion.getLabel();
    }

    /** 받침에 따른 조사 선택. 감정 라벨에는 쓰지 않는다 — 귀찮아서는 명사가 아니라 절이다. */
    private static String withJosa(String word, String withFinalConsonant, String withoutFinalConsonant) {
        return word + (hasFinalConsonant(word) ? withFinalConsonant : withoutFinalConsonant);
    }

    /**
     * 마지막 글자에 받침이 있는지. 한글 음절은 0xAC00부터 종성 28개 단위로 배열돼 나머지가 0이면 받침이 없다.
     * 한글이 아닌 글자로 끝나면 받침 있는 쪽을 고른다 — 카페(2호점)이가 덜 어색하다.
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

    /** 천 단위 구분자. 응답에 그대로 나가는 문자열이라 로케일을 고정한다. */
    private static String formatAmount(long amount) {
        return String.format(Locale.KOREA, "%,d", amount);
    }

    /** 기간 총액 중 한 항목이 차지하는 비중(정수 %). 분모는 항상 기간 총액이다. */
    private static int shareOf(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }
}
