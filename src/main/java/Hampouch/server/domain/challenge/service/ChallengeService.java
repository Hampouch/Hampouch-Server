package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.expense.service.ExpenseSpendingQuery;
import Hampouch.server.domain.expense.service.PeriodSpending;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final ExpenseSpendingQuery expenseSpendingQuery;
    private final ChallengeAdjustmentRepository challengeAdjustmentRepository;
    private final UserRestRepository userRestRepository; // UserRestService와의 순환 의존을 피한다.
    private final UserOperationLock userOperationLock;
    private final Clock clock; // 배포 환경의 기본 시간대와 무관하게 한국 날짜를 사용한다.

    /** 진행 중 챌린지가 없을 때 생성하고 활성 휴식은 오늘 종료한다. */
    @Transactional
    public CreateChallengeResponse create(Long userId, CreateChallengeRequest req) {
        userOperationLock.lock(userId);
        if (finalizeExpiredAndCheckActiveChallenge(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        // 챌린지 생성은 복귀 의사로 간주해 옛 휴식이 나중에 다시 활성화되지 않도록 오늘 종료한다.
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
            // IDENTITY INSERT가 즉시 실행되므로 조건부 UNIQUE 위반을 이 범위에서 409로 변환할 수 있다.
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        return CreateChallengeResponse.from(challenge);
    }

    /** 진행 중 챌린지를 우선하고, 없으면 휴식 홈을 반환한다. */
    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId) {
        userOperationLock.lock(userId);
        // 조회만으로 만료 상태를 확정하면 최종 종료 전 지출 수정 구간이 사라지므로 hasActiveChallenge를 사용하지 않는다.
        Optional<Challenge> inProgress = challengeRepository.findInProgress(userId);
        if (inProgress.isEmpty()) {
            return restHomeOrNotFound(userId);
        }
        Challenge c = inProgress.get();

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        LocalDate today = LocalDate.now(clock);
        ExpenseInputState expenseInputState = evaluateExpenseInputState(userId, c, today);

        // TODO: todaySpent를 ChallengeDay가 아닌 지출 원본에서 계산한다.
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
        int todayRemaining = dailyLimit - todaySpent; // 초과액을 표현하려고 음수를 허용한다.
        var consumption = new CurrentChallengeResponse.Consumption(
                todaySpent, todayRemaining, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), alertLevel);

        // TODO: 카테고리별 집계가 준비되면 WEAK_CATEGORY_ALERT를 추가한다.
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

    /** 8일 이상 챌린지에서 오늘을 제외한 연속 미입력일을 판정한다. */
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

    /** 휴식 홈 보관 기록은 직전 종료 한 건을 결과 화면과 같은 규칙으로 계산한다. */
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
            // LocalDate보다 좁은 MySQL DATE 범위를 지킨다.
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

    /** 기간 종료 후 결과를 확정한다. 최종 종료 전에는 지출 변경으로 다시 계산될 수 있다. */
    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadOwned(userId, challengeId);
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(challengeId);

        // 미입력일은 0원 지출로 간주한다.
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
        // ChallengeDay와 지출 원본이 동기화되기 전에는 요약 합계와 감정별 합계가 다를 수 있다.
        PeriodSpending spending = expenseSpendingQuery.periodSpending(userId, c.getStartDate(), c.getEndDate());
        return new ResultResponse(c.getId(), c.getStatus(), c.getClosedAt(), period, summary, spending.emotionBreakdown());
    }

    /**
     * 기록 기반 결과를 최종 종료해 해당 기간의 지출을 변경 불가로 확정한다.
     * 만료된 진행 상태는 먼저 확정하지만 포기·자동 취소는 최종 종료할 수 없다.
     */
    @Transactional
    public CloseResponse close(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadOwnedForUpdate(userId, challengeId);
        if (c.isClosed()) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED);
        }
        if (c.isInProgress() && !isExpired(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
        }
        finalizeIfExpired(c);
        if (!c.isResultFromRecords()) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_CLOSABLE);
        }
        c.close(LocalDateTime.now(clock));
        return CloseResponse.from(c);
    }

    /** 특정 날짜의 지출과 판정을 upsert한다. */
    @Transactional
    public DayUpsertResponse upsertDay(Long userId, Long challengeId, DayUpsertRequest req) {
        userOperationLock.lock(userId);
        Challenge c = loadOwnedForUpdate(userId, challengeId);
        // 상태 충돌은 요청 날짜 오류보다 우선한다.
        if (c.isClosed()) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED);
        }
        if (req.date().isBefore(c.getStartDate()) || req.date().isAfter(c.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.DAY_OUT_OF_RANGE);
        }
        // 과거 기록은 조정 후 현재 한도가 아니라 해당 날짜의 한도로 판정한다.
        int dailyLimit = timelineOf(c).on(req.date());
        DayStatus status = ChallengeCalculator.judge(req.spentAmount(), dailyLimit);

        ChallengeDay day = challengeDayRepository.findByChallenge_IdAndDayDate(challengeId, req.date())
                .map(existing -> {
                    existing.update(req.spentAmount(), status);
                    return existing;
                })
                .orElseGet(() -> challengeDayRepository.save(
                        ChallengeDay.of(c, req.date(), req.spentAmount(), status, dailyLimit)));

        // 기록 기반 결과만 수정된 지출로 재계산한다.
        if (!c.isInProgress() && c.isResultFromRecords()) {
            ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                    challengeDayRepository.findByChallenge_Id(challengeId),
                    timelineOf(c), c.getStartDate(), c.getEndDate());
            c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    /** 만료 상태를 지연 확정하므로 조회지만 쓰기 트랜잭션이 필요하다. */
    @Transactional
    public ChallengeHistoryResponse getHistory(Long userId) {
        userOperationLock.lock(userId);
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
        // 조회 정렬을 유지해 날짜가 같은 조정도 타임라인 순서가 보존된다.
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
                        userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL, ChallengeStatus.VOID))
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ENDED_CHALLENGE));

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(last.getId());
        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, timelineOf(last), last.getStartDate(), last.getEndDate());
        int recommendedDurationDays = ChallengeCalculator.recommendedDurationDays(last.getDurationDays());
        int recommendedBudgetTotal = ChallengeCalculator.recommendedBudgetTotal(
                last.getStatus(), last.getEndReason(), last.getBudgetTotal(), s.actualSpent());

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
        userOperationLock.lock(userId);
        Challenge c = loadInProgressOwned(userId, challengeId);
        c.giveUp();
        return GiveUpResponse.from(c);
    }

    /** 과거 결과의 해석이 바뀌지 않도록 진행 중 챌린지만 수정한다. */
    @Transactional
    public FocusCategoriesResponse updateFocusCategories(Long userId, Long challengeId, FocusCategoriesRequest req) {
        userOperationLock.lock(userId);
        Challenge c = loadInProgressOwned(userId, challengeId);
        c.replaceWeakCategories(req.categories());
        return FocusCategoriesResponse.from(c);
    }

    /**
     * 목표 금액을 조정하고 하루 한도를 다시 계산한다.
     * 결과 역전을 막기 위해 만료 후 조정은 금지하며 새 한도는 오늘부터 적용한다.
     */
    @Transactional
    public AdjustGoalResponse adjustGoal(Long userId, Long challengeId, AdjustGoalRequest req) {
        userOperationLock.lock(userId);
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
            // 순번 UNIQUE는 서비스 잠금을 우회한 중복 조정의 마지막 방어선이다.
            throw new CustomException(ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
        }
        rejudgeFrom(challengeId, today, newDailyLimit);

        return new AdjustGoalResponse(challengeId, newBudgetTotal, newDailyLimit, usedCount + 1, maxCount);
    }

    /** 효력일부터 미리 입력된 기록도 새 한도로 재판정하며 과거 기록은 유지한다. */
    private void rejudgeFrom(Long challengeId, LocalDate effectiveDate, int dailyLimit) {
        challengeDayRepository.findByChallenge_IdAndDayDateGreaterThanEqual(challengeId, effectiveDate)
                .forEach(day -> day.rejudge(
                        ChallengeCalculator.judge(day.getSpentAmount(), dailyLimit), dailyLimit));
    }

    /**
     * 만료된 진행 상태를 지연 확정한 뒤 실제 진행 여부를 반환한다.
     * 생성과 휴식 시작은 직접 exists 조회하지 않고 이 경로를 사용한다.
     */
    @Transactional
    public boolean hasActiveChallenge(Long userId) {
        userOperationLock.lock(userId);
        return finalizeExpiredAndCheckActiveChallenge(userId);
    }

    private boolean finalizeExpiredAndCheckActiveChallenge(Long userId) {
        finalizeExpiredInProgress(userId);
        return challengeRepository.existsInProgress(userId);
    }

    /** 배치가 없으므로 접근 시점에 만료 상태를 확정한다. */
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

    /**
     * 만료 상태를 바꾸지 않고 409로 거절한다.
     * 먼저 확정한 뒤 예외를 던지면 확정까지 함께 롤백된다.
     */
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

    private Challenge loadOwnedForUpdate(Long userId, Long challengeId) {
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwnedBy(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_FORBIDDEN);
        }
        return c;
    }

    /** 오늘 기록이 있을 때만 오늘을 포함해 미입력을 선제 성공으로 세지 않는다. */
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
