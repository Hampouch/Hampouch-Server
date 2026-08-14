package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.exception.ChallengeNotClosableException;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.expense.service.ExpenseSpendingQuery;
import Hampouch.server.domain.expense.service.PeriodSpending;
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

    private static final int MISSING_INPUT_WARNING_DAYS = 2;
    private static final List<ChallengeStatus> COMPLETED_STATUSES =
            List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL);

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
        if (finalizeDueAndCheckActiveChallenge(userId)) {
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

    /** 날짜를 생략한 조회는 오늘을 선택 날짜로 사용한다. */
    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId) {
        userOperationLock.lock(userId);
        // 조회만으로 기간 종료 결과를 확정하면 최종 종료 전 지출 수정 구간이 사라지므로 hasActiveChallenge를 사용하지 않는다.
        LocalDate today = LocalDate.now(clock);
        Optional<Challenge> challenge = challengeRepository.findContainingDate(userId, today);
        if (challenge.isEmpty()) {
            return CurrentChallengeResponse.empty();
        }
        Challenge c = challenge.get();

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        ExpenseInputState expenseInputState = evaluateExpenseInputState(userId, c, today);

        Optional<ChallengeDay> todayRow = challengeDayRepository.findByChallenge_IdAndDayDate(c.getId(), today);
        DailyLimitTimeline limits = timelineOf(c);
        return buildChallengeResponse(c, days, today, todayRow, c.getDailyLimit(), limits,
                expenseInputState, challengeAdjustmentRepository.countByChallenge_Id(c.getId()));
    }

    /** 선택 날짜의 챌린지가 있으면 현황을, 없으면 빈 상태를 반환한다. */
    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId, LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }
        if (date.equals(today)) {
            return getCurrent(userId);
        }

        Optional<Challenge> challenge = challengeRepository.findContainingDate(userId, date);
        if (challenge.isEmpty()) {
            return CurrentChallengeResponse.empty();
        }

        Challenge c = challenge.get();
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        List<ChallengeAdjustment> adjustments = challengeAdjustmentRepository
                .findByChallenge_IdOrderByEffectiveDateAscIdAsc(c.getId());
        DailyLimitTimeline limits = DailyLimitTimeline.of(c, adjustments);
        Optional<ChallengeDay> selectedDay = days.stream()
                .filter(day -> day.getDayDate().equals(date))
                .findFirst();
        int dailyLimit = selectedDay.map(ChallengeDay::getDailyLimit).orElseGet(() -> limits.on(date));
        int usedAdjustmentCount = (int) adjustments.stream()
                .filter(adjustment -> !adjustment.getEffectiveDate().isAfter(date))
                .count();

        return buildChallengeResponse(c, days, date, selectedDay, dailyLimit, limits,
                ExpenseInputState.NORMAL, usedAdjustmentCount);
    }

    private CurrentChallengeResponse buildChallengeResponse(
            Challenge c,
            List<ChallengeDay> days,
            LocalDate selectedDate,
            Optional<ChallengeDay> selectedDay,
            int dailyLimit,
            DailyLimitTimeline limits,
            ExpenseInputState expenseInputState,
            int usedAdjustmentCount) {
        LocalDate aggregationEndDate = selectedDate.isAfter(c.getEndDate()) ? c.getEndDate() : selectedDate;
        ChallengeSummary summary = aggregationEndDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0)
                : ChallengeCalculator.summarizeThrough(days, limits, c.getStartDate(), aggregationEndDate);

        int spent = selectedDay.map(ChallengeDay::getSpentAmount).orElse(0);
        double usageRate = ChallengeCalculator.usageRate(spent, dailyLimit);
        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), dailyLimit, c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, selectedDate), remainingDays(c, selectedDate),
                summary.successDays(), summary.overDays(),
                ChallengeCalculator.currentStreakAsOf(days, c.getStartDate(), aggregationEndDate),
                summary.savedAmount());
        var consumption = new CurrentChallengeResponse.Consumption(
                spent, dailyLimit - spent, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), AlertLevel.of(usageRate));

        List<WarningCard> warningCards = new ArrayList<>();
        if (c.isInProgress() && ChallengeCalculator.isGoalTooTight(days, c.getStartDate(), aggregationEndDate)) {
            warningCards.add(WarningCard.GOAL_TOO_TIGHT);
        }
        var adjustment = new CurrentChallengeResponse.Adjustment(
                usedAdjustmentCount, ChallengeCalculator.maxAdjustmentCount(c.getDurationDays()));

        return CurrentChallengeResponse.forChallenge(
                view, progress, consumption, warningCards, expenseInputState, adjustment);
    }

    /** 8일 이상 챌린지에서 진행 중에는 어제까지, 기간 종료 후에는 종료일까지 마지막 연속 미입력을 판정한다. */
    private ExpenseInputState evaluateExpenseInputState(Long userId, Challenge challenge, LocalDate today) {
        if (challenge.getDurationDays() < Challenge.AUTO_CANCEL_MIN_DURATION_DAYS
                || today.isBefore(challenge.getStartDate())) {
            return ExpenseInputState.NORMAL;
        }

        int missingDays = 0;
        // 미입력 판정에 포함할 마지막 날짜: 기간 중에는 어제, 기간 종료 후에는 종료일.
        LocalDate judgmentEndDate = today.isAfter(challenge.getEndDate())
                ? challenge.getEndDate()
                : today.minusDays(1);
        LocalDate dateToCheck = judgmentEndDate;
        while (!dateToCheck.isBefore(challenge.getStartDate())
                && missingDays < Challenge.MISSING_INPUT_CANCEL_DAYS) {
            if (expenseService.hasDayRecord(userId, dateToCheck)) {
                break;
            }
            missingDays++;
            dateToCheck = dateToCheck.minusDays(1);
        }

        if (missingDays == Challenge.MISSING_INPUT_CANCEL_DAYS) {
            challenge.cancelForMissingInput(judgmentEndDate);
            return ExpenseInputState.AUTO_CANCELLED;
        }
        if (missingDays == MISSING_INPUT_WARNING_DAYS) {
            return ExpenseInputState.TWO_DAYS_MISSING;
        }
        return ExpenseInputState.NORMAL;
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

    /** 기간 종료 후 3일 미입력 무효를 먼저 판정하고, 무효가 아니면 금액으로 결과를 확정한다. */
    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadOwned(userId, challengeId);
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(challengeId);
        LocalDate aggregationEndDate = aggregationEndDate(c);

        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                days, timelineOf(c), c.getStartDate(), aggregationEndDate);

        if (c.isInProgress()) {
            LocalDate today = LocalDate.now(clock);
            if (!today.isAfter(c.getEndDate())) {
                throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
            }
            if (evaluateExpenseInputState(userId, c, today) != ExpenseInputState.AUTO_CANCELLED) {
                c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
            }
        }
        var period = ResultResponse.Period.from(c);
        var summary = new ResultResponse.Summary(
                s.successDays(), s.overDays(), s.savedAmount(), s.overAmount(),
                s.maxStreak(), c.getBudgetTotal(), s.actualSpent());
        // ChallengeDay와 지출 원본이 동기화되기 전에는 요약 합계와 감정별 합계가 다를 수 있다.
        PeriodSpending spending = aggregationEndDate.isBefore(c.getStartDate())
                ? new PeriodSpending(0, List.of())
                : expenseSpendingQuery.periodSpending(userId, c.getStartDate(), aggregationEndDate);
        return new ResultResponse(c.getId(), c.getStatus(), c.getExpenseLockedAt(), period, summary, spending.emotionBreakdown());
    }

    /**
     * 기록 기반 결과를 최종 종료해 해당 기간의 지출을 변경 불가로 확정한다.
     * 기간이 종료된 진행 상태는 먼저 확정하지만 포기·자동 취소는 최종 종료할 수 없다.
     * 자동 취소 후 반환하는 409가 VOID 확정까지 롤백하지 않도록 해당 예외만 롤백 대상에서 뺀다.
     */
    @Transactional(noRollbackFor = ChallengeNotClosableException.class)
    public CloseResponse close(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadOwnedForUpdate(userId, challengeId);
        if (c.isExpenseLocked()) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED);
        }
        if (c.isInProgress() && !hasPeriodEnded(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
        }
        applyDueFinalization(userId, c, LocalDate.now(clock));
        if (!c.isResultFromRecords()) {
            throw new ChallengeNotClosableException();
        }
        c.lockExpenseChanges(LocalDateTime.now(clock));
        return CloseResponse.from(c);
    }

    /** 특정 날짜의 지출과 판정을 upsert한다. */
    @Transactional
    public DayUpsertResponse upsertDay(Long userId, Long challengeId, DayUpsertRequest req) {
        userOperationLock.lock(userId);
        Challenge c = loadOwnedForUpdate(userId, challengeId);
        // 상태 충돌은 요청 날짜 오류보다 우선한다.
        if (c.isExpenseLocked()) {
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
            ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                    challengeDayRepository.findByChallenge_Id(challengeId),
                    timelineOf(c), c.getStartDate(), c.getEndDate());
            c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    /** 이미 확정된 지난 챌린지의 집계만 반환한다. */
    public ChallengeHistoryResponse getHistory(Long userId) {
        List<Challenge> ended = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                userId, COMPLETED_STATUSES);
        if (ended.isEmpty()) {
            return new ChallengeHistoryResponse(List.of());
        }

        Map<Long, ChallengeSummary> summariesByChallengeId = summarizeCompletedChallenges(ended);
        List<ChallengeHistoryResponse.Item> items = ended.stream()
                .map(c -> {
                    ChallengeSummary s = summariesByChallengeId.get(c.getId());
                    return ChallengeHistoryResponse.Item.of(c, s.actualSpent(), s.savedAmount());
                })
                .toList();
        return new ChallengeHistoryResponse(items);
    }

    private Map<Long, ChallengeSummary> summarizeCompletedChallenges(List<Challenge> completed) {
        if (completed.isEmpty()) {
            return Map.of();
        }
        List<Long> completedIds = completed.stream().map(Challenge::getId).toList();
        Map<Long, List<ChallengeDay>> daysByChallengeId = challengeDayRepository
                .findByChallenge_IdIn(completedIds)
                .stream()
                .collect(Collectors.groupingBy(d -> d.getChallenge().getId()));
        Map<Long, List<ChallengeAdjustment>> adjustmentsByChallengeId = challengeAdjustmentRepository
                .findByChallenge_IdInOrderByEffectiveDateAscIdAsc(completedIds)
                .stream()
                .collect(Collectors.groupingBy(a -> a.getChallenge().getId()));

        return completed.stream().collect(Collectors.toMap(
                Challenge::getId,
                challenge -> ChallengeCalculator.summarizeThrough(
                        daysByChallengeId.getOrDefault(challenge.getId(), List.of()),
                        DailyLimitTimeline.of(
                                challenge, adjustmentsByChallengeId.getOrDefault(challenge.getId(), List.of())),
                        challenge.getStartDate(), aggregationEndDate(challenge))));
    }

    /** 기간 마지막 날까지 진행 중 챌린지를 즉시 실패로 확정한다. */
    @Transactional
    public GiveUpResponse giveUp(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadInProgressOwned(userId, challengeId);
        c.giveUp(LocalDate.now(clock));
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
     * 결과 역전을 막기 위해 기간 종료 후 조정은 금지하며 새 한도는 오늘부터 적용한다.
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

    /** 정기 확정 전에 생성·휴식 요청이 들어오면 미입력·기간 종료 상태를 보정한 뒤 실제 진행 여부를 반환한다. */
    @Transactional
    public boolean hasActiveChallenge(Long userId) {
        userOperationLock.lock(userId);
        return finalizeDueAndCheckActiveChallenge(userId);
    }

    /** 정기 작업 대상 한 건을 사용자 단위 상태 변경과 직렬화해 판정한다. */
    @Transactional
    public void finalizeDueChallenge(Long userId, Long challengeId, LocalDate today) {
        userOperationLock.lock(userId);
        challengeRepository.findById(challengeId)
                .filter(challenge -> challenge.isOwnedBy(userId))
                .ifPresent(challenge -> applyDueFinalization(userId, challenge, today));
    }

    /**
     * 정기 작업이 아직 처리하지 못한 3일 미입력·기간 종료 챌린지를 먼저 확정한다.
     * 그 뒤에도 IN_PROGRESS가 남아 있으면 true를 반환해 새 챌린지 생성·휴식 시작을 막는다.
     */
    private boolean finalizeDueAndCheckActiveChallenge(Long userId) {
        finalizeDueInProgress(userId);
        return challengeRepository.existsInProgress(userId);
    }

    /** 정기 확정 전에 관련 요청이 들어오면 3일 미입력과 기간 종료를 같은 규칙으로 보정한다. */
    private void finalizeDueInProgress(Long userId) {
        LocalDate today = LocalDate.now(clock);
        challengeRepository.findInProgress(userId)
                .ifPresent(challenge -> applyDueFinalization(userId, challenge, today));
    }

    private void applyDueFinalization(Long userId, Challenge c, LocalDate today) {
        if (!c.isInProgress()) {
            return;
        }
        if (evaluateExpenseInputState(userId, c, today) == ExpenseInputState.AUTO_CANCELLED) {
            return;
        }
        if (!today.isAfter(c.getEndDate())) {
            return;
        }
        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                challengeDayRepository.findByChallenge_Id(c.getId()),
                timelineOf(c), c.getStartDate(), c.getEndDate());
        c.applyResult(ChallengeCalculator.resultStatus(s.actualSpent(), c.getBudgetTotal()));
    }

    private boolean hasPeriodEnded(Challenge c) {
        return LocalDate.now(clock).isAfter(c.getEndDate());
    }

    private DailyLimitTimeline timelineOf(Challenge c) {
        return DailyLimitTimeline.of(c,
                challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(c.getId()));
    }

    // 결과 조회의 성공일·초과일·절약액·초과액·최고 스트릭·총지출·감정별 지출과
    // 지난 챌린지 목록의 총지출·절약액에 포함할 마지막 날짜를 반환한다. 선택 날짜 조회에는 쓰지 않는다.
    // 기간 종료 챌린지는 endDate, 포기·자동 취소 챌린지는 inactiveFrom 전날까지 포함한다.
    private LocalDate aggregationEndDate(Challenge challenge) {
        if (challenge.getEndReason() == null) {
            return challenge.getEndDate();
        }
        LocalDate activeThrough = Objects.requireNonNull(
                challenge.getInactiveFrom(), "종료 사유가 있는 챌린지의 inactiveFrom은 필수입니다.")
                .minusDays(1);
        return activeThrough;
    }

    /**
     * 기간 종료 결과를 확정하지 않고 409로 거절한다.
     * 먼저 확정한 뒤 예외를 던지면 확정까지 함께 롤백된다.
     */
    private Challenge loadInProgressOwned(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        if (!c.isInProgress() || hasPeriodEnded(c)) {
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
