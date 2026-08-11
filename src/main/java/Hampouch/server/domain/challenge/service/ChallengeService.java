package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeService {

    private static final int AUTO_CANCEL_MIN_DURATION_DAYS = 8;
    private static final int MISSING_INPUT_WARNING_DAYS = 2;
    private static final int MISSING_INPUT_CANCEL_DAYS = 3;

    private final ChallengeRepository challengeRepository;
    private final ChallengeDayRepository challengeDayRepository;
    private final ExpenseService expenseService;
    private final ChallengeAdjustmentRepository challengeAdjustmentRepository;
    // UserRestService와의 순환 의존을 피하려고 리포지토리를 직접 사용한다.
    private final UserRestRepository userRestRepository;
    // 배포 환경의 시스템 시간대와 무관하게 서비스 날짜를 계산한다.
    private final Clock clock;

    @Transactional
    public CreateChallengeResponse create(Long userId, CreateChallengeRequest req) {
        if (finalizeExpiredAndCheckActiveChallenge(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        // 새 챌린지 생성은 복귀 의사이므로 활성 휴식을 닫아 두 상태가 함께 남지 않게 한다.
        LocalDate today = LocalDate.now(clock);
        userRestRepository.findActiveOn(userId, today).ifPresent(rest -> rest.resume(today));
        int dailyLimit = ChallengeCalculator.dailyLimit(req.budgetTotal(), req.durationDays());
        Challenge challenge = Challenge.builder()
                .userId(userId)
                .durationDays(req.durationDays())
                .startDate(req.startDate())
                .budgetTotal(req.budgetTotal())
                .dailyLimit(dailyLimit)
                .resetByPayday(req.resetByPaydayOrFalse())
                .paydayDay(req.paydayDay())
                .build();
        if (req.weakCategories() != null) {
            challenge.replaceWeakCategories(req.weakCategories());
        }
        try {
            challengeRepository.save(challenge);
        } catch (DataIntegrityViolationException e) {
            // 동시 생성 경쟁은 DB 유니크 제약에서 같은 409로 변환한다.
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        return CreateChallengeResponse.from(challenge);
    }

    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId) {
        // 종료 팝업 전 수정 구간을 보존하려고 이 조회에서는 만료 상태를 확정하지 않는다.
        Optional<Challenge> inProgress = challengeRepository.findInProgress(userId);
        if (inProgress.isEmpty()) {
            return restHomeOrNotFound(userId);
        }
        Challenge c = inProgress.get();

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        LocalDate today = LocalDate.now(clock);
        ExpenseInputState expenseInputState = evaluateExpenseInputState(userId, c, today);

        // TODO: todaySpent 출처를 지출 도메인으로 교체한다.
        int dailyLimit = c.getDailyLimit();
        Optional<ChallengeDay> todayRow = challengeDayRepository.findByChallenge_IdAndDayDate(c.getId(), today);
        int todaySpent = todayRow.map(ChallengeDay::getSpentAmount).orElse(0);
        double usageRate = ChallengeCalculator.usageRate(todaySpent, dailyLimit);
        AlertLevel alertLevel = AlertLevel.of(usageRate);

        LocalDate lastJudgedDate = lastJudgedDate(c, today, todayRow.isPresent());
        ChallengeSummary s = lastJudgedDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0)
                : ChallengeCalculator.summarizeForResult(days, timelineOf(c), c.getStartDate(), lastJudgedDate);

        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), c.getDailyLimit(), c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, today), remainingDays(c, today),
                s.successDays(), s.overDays(),
                ChallengeCalculator.currentStreakAsOf(days, c.getStartDate(), lastJudgedDate), s.savedAmount());
        int todayRemaining = dailyLimit - todaySpent; // 초과분은 음수로 전달한다.
        var consumption = new CurrentChallengeResponse.Consumption(
                todaySpent, todayRemaining, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), alertLevel);

        // TODO(#52): 카테고리별 집계가 준비되면 WEAK_CATEGORY_ALERT를 구현한다.
        List<WarningCard> warningCards = new ArrayList<>();
        if (c.isInProgress() && ChallengeCalculator.isGoalTooTight(days, c.getStartDate(), lastJudgedDate)) {
            warningCards.add(WarningCard.GOAL_TOO_TIGHT);
        }

        var adjustment = new CurrentChallengeResponse.Adjustment(
                challengeAdjustmentRepository.countByChallenge_Id(c.getId()),
                ChallengeCalculator.maxAdjustmentCount(c.getDurationDays()));

        return CurrentChallengeResponse.forChallenge(
                view, progress, consumption, warningCards, expenseInputState, adjustment);
    }

    private ExpenseInputState evaluateExpenseInputState(Long userId, Challenge challenge, LocalDate today) {
        if (challenge.getDurationDays() < AUTO_CANCEL_MIN_DURATION_DAYS
                || today.isBefore(challenge.getStartDate())) {
            return ExpenseInputState.NORMAL;
        }

        int missingDays = 0;
        LocalDate dateToCheck = today.isAfter(challenge.getEndDate())
                ? challenge.getEndDate()
                : today.minusDays(1);
        while (!dateToCheck.isBefore(challenge.getStartDate()) && missingDays < MISSING_INPUT_CANCEL_DAYS) {
            if (expenseService.hasDayRecord(userId, dateToCheck)) {
                break;
            }
            missingDays++;
            dateToCheck = dateToCheck.minusDays(1);
        }

        if (missingDays == MISSING_INPUT_CANCEL_DAYS) {
            challenge.cancelForMissingInput();
            return ExpenseInputState.AUTO_CANCELLED;
        }
        if (missingDays == MISSING_INPUT_WARNING_DAYS) {
            return ExpenseInputState.TWO_DAYS_MISSING;
        }
        return ExpenseInputState.NORMAL;
    }

    private CurrentChallengeResponse restHomeOrNotFound(Long userId) {
        UserRest rest = userRestRepository.findActiveOn(userId, LocalDate.now(clock))
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ACTIVE_CHALLENGE));
        return CurrentChallengeResponse.forRest(CurrentChallengeResponse.RestView.from(rest), keptRecords(userId));
    }

    /** 누계 범위 확정 전까지 직전 종료 챌린지만 집계하고, 기록이 없으면 0으로 응답한다. */
    private CurrentChallengeResponse.KeptRecords keptRecords(Long userId) {
        return challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL))
                .map(prev -> {
                    ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                            challengeDayRepository.findByChallenge_Id(prev.getId()),
                            timelineOf(prev), prev.getStartDate(), prev.getEndDate());
                    return new CurrentChallengeResponse.KeptRecords(s.savedAmount(), s.maxStreak());
                })
                .orElse(new CurrentChallengeResponse.KeptRecords(0, 0));
    }

    public CalendarResponse getCalendar(Long userId, Long challengeId, int year, int month) {
        Challenge c = loadOwned(userId, challengeId);
        if (month < 1 || month > 12) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }
        if (year < 1 || year > 9999) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }

        DateRange challengeRangeInMonth = DateRange.ofMonth(year, month)
                .intersect(new DateRange(c.getStartDate(), c.getEndDate()));

        List<CalendarResponse.DayView> days = challengeRangeInMonth.isEmpty()
                ? List.of()
                : challengeDayRepository.findByChallenge_IdAndDayDateBetween(
                                challengeId, challengeRangeInMonth.start(), challengeRangeInMonth.end()).stream()
                        .sorted(Comparator.comparing(ChallengeDay::getDayDate))
                        .map(d -> new CalendarResponse.DayView(d.getDayDate(), d.getStatus(), d.getSpentAmount()))
                        .toList();
        return new CalendarResponse(challengeId, year, month, days);
    }

    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(challengeId);

        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, timelineOf(c), c.getStartDate(), c.getEndDate());

        if (c.isInProgress()) {
            LocalDate today = LocalDate.now(clock);
            if (!today.isAfter(c.getEndDate())) {
                throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
            }
            c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
        }
        var period = ResultResponse.Period.from(c);
        var summary = new ResultResponse.Summary(
                s.successDays(), s.overDays(), s.savedAmount(), s.overAmount(),
                s.maxStreak(), c.getBudgetTotal(), s.actualSpent());
        // TODO: 지출 도메인 연동 후 카테고리·감정 집계를 채운다.
        return new ResultResponse(c.getId(), c.getStatus(), period, summary, List.of(), List.of());
    }

    @Transactional
    public DayUpsertResponse upsertDay(Long userId, Long challengeId, DayUpsertRequest req) {
        Challenge c = loadOwned(userId, challengeId);
        if (req.date().isBefore(c.getStartDate()) || req.date().isAfter(c.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.DAY_OUT_OF_RANGE);
        }
        // 날짜별 한도 스냅샷을 사용해 목표 조정이 지난 기록에 소급되지 않게 한다.
        int dailyLimit = timelineOf(c).on(req.date());
        DayStatus status = ChallengeCalculator.judge(req.spentAmount(), dailyLimit);

        ChallengeDay day = challengeDayRepository.findByChallenge_IdAndDayDate(challengeId, req.date())
                .map(existing -> {
                    existing.update(req.spentAmount(), status);
                    return existing;
                })
                .orElseGet(() -> challengeDayRepository.save(
                        ChallengeDay.of(c, req.date(), req.spentAmount(), status, dailyLimit)));

        // 선언 종료와 자동 취소 결과는 지출 수정으로 되살리지 않는다.
        if (!c.isInProgress() && c.getEndReason() == null) {
            ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                    challengeDayRepository.findByChallenge_Id(challengeId),
                    timelineOf(c), c.getStartDate(), c.getEndDate());
            c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    // 조회 중 만료 상태를 확정하므로 쓰기 트랜잭션이 필요하다.
    @Transactional
    public ChallengeHistoryResponse getHistory(Long userId) {
        finalizeExpiredInProgress(userId);
        List<Challenge> ended = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL));
        if (ended.isEmpty()) {
            return new ChallengeHistoryResponse(List.of());
        }

        List<Long> endedIds = ended.stream().map(Challenge::getId).toList();
        Map<Long, List<ChallengeDay>> daysByChallengeId = challengeDayRepository
                .findByChallenge_IdIn(endedIds)
                .stream()
                .collect(Collectors.groupingBy(d -> d.getChallenge().getId()));
        Map<Long, List<ChallengeAdjustment>> adjustmentsByChallengeId = challengeAdjustmentRepository
                .findByChallenge_IdInOrderByEffectiveDateAscIdAsc(endedIds)
                .stream()
                .collect(Collectors.groupingBy(a -> a.getChallenge().getId()));

        List<ChallengeHistoryResponse.Item> items = ended.stream()
                .map(c -> {
                    ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                            daysByChallengeId.getOrDefault(c.getId(), List.of()),
                            DailyLimitTimeline.of(c, adjustmentsByChallengeId.getOrDefault(c.getId(), List.of())),
                            c.getStartDate(), c.getEndDate());
                    return ChallengeHistoryResponse.Item.of(c, s.actualSpent(), s.savedAmount());
                })
                .toList();
        return new ChallengeHistoryResponse(items);
    }

    @Transactional
    public RecommendationResponse getRecommendation(Long userId) {
        finalizeExpiredInProgress(userId);
        Challenge last = challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL))
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ENDED_CHALLENGE));

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(last.getId());
        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, timelineOf(last), last.getStartDate(), last.getEndDate());
        int recommendedDurationDays = ChallengeCalculator.recommendedDurationDays(last.getDurationDays());
        int recommendedBudgetTotal = ChallengeCalculator.recommendedBudgetTotal(last.getBudgetTotal());

        return new RecommendationResponse(
                recommendationMessage(
                        last.getStatus(),
                        last.getBudgetTotal(),
                        s.actualSpent(),
                        recommendedDurationDays,
                        recommendedBudgetTotal));
    }

    static String recommendationMessage(ChallengeStatus status, int budgetTotal, int actualSpent,
                                        int recommendedDurationDays, int recommendedBudgetTotal) {
        int saved = budgetTotal - actualSpent;
        if (status == ChallengeStatus.SUCCESS) {
            String result;
            if (saved > 0) {
                result = String.format(Locale.KOREA, "목표보다 %,d원 절약했어요!", saved);
            } else if (saved == 0) {
                result = "목표 금액을 정확히 지켰어요!";
            } else {
                throw new IllegalStateException("성공한 챌린지의 실지출이 목표 금액을 초과했습니다.");
            }
            String nextStep = successNextStep(budgetTotal, recommendedBudgetTotal);
            return result + nextStep + recommendationPlan(
                    budgetTotal, recommendedDurationDays, recommendedBudgetTotal);
        }
        String result;
        if (saved > 0) {
            result = String.format(Locale.KOREA, "목표보다 %,d원 절약했지만 이번엔 아쉽게 끝났어요.", saved);
        } else if (saved < 0) {
            result = String.format(Locale.KOREA, "목표보다 %,d원 초과했어요. 이번엔 다시 도전해볼까요?", -saved);
        } else {
            result = "목표 금액과 같지만 이번엔 아쉽게 끝났어요.";
        }
        return result + recommendationPlan(budgetTotal, recommendedDurationDays, recommendedBudgetTotal);
    }

    private static String successNextStep(int previousBudgetTotal, int recommendedBudgetTotal) {
        if (recommendedBudgetTotal < previousBudgetTotal) {
            return " 이번엔 조금 더 타이트하게 가볼까요?";
        }
        if (recommendedBudgetTotal > previousBudgetTotal) {
            return " 이번엔 조금 더 여유 있게 가볼까요?";
        }
        return " 이번에도 같은 목표로 이어가볼까요?";
    }

    private static String recommendationPlan(int previousBudgetTotal, int recommendedDurationDays,
                                             int recommendedBudgetTotal) {
        String budgetPlan;
        if (recommendedBudgetTotal < previousBudgetTotal) {
            budgetPlan = String.format(Locale.KOREA, "목표는 %,d원으로 줄여서", recommendedBudgetTotal);
        } else if (recommendedBudgetTotal > previousBudgetTotal) {
            budgetPlan = String.format(Locale.KOREA, "목표는 %,d원으로 늘려서", recommendedBudgetTotal);
        } else {
            budgetPlan = String.format(Locale.KOREA, "목표는 그대로 %,d원으로", recommendedBudgetTotal);
        }
        return String.format(Locale.KOREA,
                " 기간은 그대로 %d일, %s 새 기록에 도전해봐요.",
                recommendedDurationDays, budgetPlan);
    }

    @Transactional
    public GiveUpResponse giveUp(Long userId, Long challengeId) {
        Challenge c = loadInProgressOwned(userId, challengeId);
        c.giveUp();
        return GiveUpResponse.from(c);
    }

    // 수정 범위가 확정되기 전까지 종료된 챌린지의 카테고리는 잠근다.
    @Transactional
    public FocusCategoriesResponse updateFocusCategories(Long userId, Long challengeId, FocusCategoriesRequest req) {
        Challenge c = loadInProgressOwned(userId, challengeId);
        c.replaceWeakCategories(req.categories());
        return FocusCategoriesResponse.from(c);
    }

    // 세부 계약 확정 전까지 만료 후 조정을 막고 조정 당일부터 새 한도를 적용한다.
    @Transactional
    public AdjustGoalResponse adjustGoal(Long userId, Long challengeId, AdjustGoalRequest req) {
        Challenge c = loadInProgressOwned(userId, challengeId);
        int usedCount = challengeAdjustmentRepository.countByChallenge_Id(challengeId);
        int maxCount = ChallengeCalculator.maxAdjustmentCount(c.getDurationDays());
        if (usedCount >= maxCount) {
            throw new CustomException(ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
        }

        LocalDate today = LocalDate.now(clock);
        int previousBudgetTotal = c.getBudgetTotal();
        int previousDailyLimit = c.getDailyLimit();
        int newBudgetTotal = req.option() != null
                ? req.option().apply(previousBudgetTotal)
                : req.budgetTotal();
        int newDailyLimit = ChallengeCalculator.dailyLimit(newBudgetTotal, c.getDurationDays());
        c.adjustGoal(newBudgetTotal, newDailyLimit);
        try {
            challengeAdjustmentRepository.saveAndFlush(ChallengeAdjustment.builder()
                    .challenge(c)
                    .sequenceNumber(usedCount + 1)
                    .effectiveDate(today)
                    .option(req.option())
                    .previousBudgetTotal(previousBudgetTotal)
                    .newBudgetTotal(newBudgetTotal)
                    .previousDailyLimit(previousDailyLimit)
                    .newDailyLimit(newDailyLimit)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시 조정 경쟁은 DB 유니크 제약에서 같은 409로 변환한다.
            throw new CustomException(ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
        }
        rejudgeFrom(challengeId, today, newDailyLimit);

        return new AdjustGoalResponse(challengeId, newBudgetTotal, newDailyLimit, usedCount + 1, maxCount);
    }

    /** 미래 날짜 기록도 받을 수 있어 효력일 이후 기록을 모두 재채점하고 과거 기록은 유지한다. */
    private void rejudgeFrom(Long challengeId, LocalDate effectiveDate, int dailyLimit) {
        challengeDayRepository.findByChallenge_IdAndDayDateGreaterThanEqual(challengeId, effectiveDate)
                .forEach(day -> day.rejudge(
                        ChallengeCalculator.judge(day.getSpentAmount(), dailyLimit), dailyLimit));
    }

    @Transactional
    public boolean hasActiveChallenge(Long userId) {
        return finalizeExpiredAndCheckActiveChallenge(userId);
    }

    private boolean finalizeExpiredAndCheckActiveChallenge(Long userId) {
        finalizeExpiredInProgress(userId);
        return challengeRepository.existsInProgress(userId);
    }

    private void finalizeExpiredInProgress(Long userId) {
        challengeRepository.findInProgress(userId).ifPresent(this::finalizeIfExpired);
    }

    private void finalizeIfExpired(Challenge c) {
        if (c.isInProgress() && isExpired(c)) {
            ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                    challengeDayRepository.findByChallenge_Id(c.getId()),
                    timelineOf(c), c.getStartDate(), c.getEndDate());
            c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
        }
    }

    private boolean isExpired(Challenge c) {
        return LocalDate.now(clock).isAfter(c.getEndDate());
    }

    private DailyLimitTimeline timelineOf(Challenge c) {
        return DailyLimitTimeline.of(c,
                challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(c.getId()));
    }

    /** 만료 상태를 확정한 뒤 409를 던지면 트랜잭션이 롤백되므로 상태는 바꾸지 않고 차단한다. */
    private Challenge loadInProgressOwned(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        if (!c.isInProgress() || isExpired(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
        }
        return c;
    }

    private Challenge loadOwned(Long userId, Long challengeId) {
        Challenge c = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwnedBy(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_FORBIDDEN);
        }
        return c;
    }

    /** 오늘 기록이 없으면 미입력일 성공 규칙이 오늘을 조기 집계하지 않도록 어제까지만 센다. */
    private static LocalDate lastJudgedDate(Challenge c, LocalDate today, boolean todayRecorded) {
        LocalDate last = todayRecorded ? today : today.minusDays(1);
        return last.isAfter(c.getEndDate()) ? c.getEndDate() : last;
    }

    private int elapsedDays(Challenge c, LocalDate today) {
        if (today.isBefore(c.getStartDate())) {
            return 0;
        }
        LocalDate last = today.isAfter(c.getEndDate()) ? c.getEndDate() : today;
        return (int) (ChronoUnit.DAYS.between(c.getStartDate(), last) + 1);
    }

    private int remainingDays(Challenge c, LocalDate today) {
        if (today.isAfter(c.getEndDate())) {
            return 0;
        }
        LocalDate base = today.isBefore(c.getStartDate()) ? c.getStartDate() : today;
        return (int) ChronoUnit.DAYS.between(base, c.getEndDate());
    }
}
