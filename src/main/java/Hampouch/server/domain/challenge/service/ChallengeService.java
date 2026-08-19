package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.exception.ChallengeNotClosableException;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.entity.NoSpendDay;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.expense.service.ExpenseSpendingQuery;
import Hampouch.server.domain.expense.service.PeriodSpending;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
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

    private final ChallengeRepository challengeRepository;
    private final ChallengeDayRepository challengeDayRepository;
    private final NoSpendDayRepository noSpendDayRepository;
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
        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = req.startDate();
        int durationDays;
        if (req.fixedDay() != null) {
            // 날짜 고정의 최초 챌린지는 설정 당일 즉시 시작한다 — 미래 시작일은 계약 밖이다.
            if (startDate.isAfter(today)) {
                throw new CustomException(CommonErrorCode.BAD_REQUEST);
            }
            durationDays = FixedDateChallengeCycle.startingOn(startDate, req.fixedDay()).durationDays();
        } else {
            durationDays = req.durationDays();
        }
        userRestRepository.findActiveOn(userId, today).ifPresent(rest -> rest.resume(today));
        int dailyLimit = ChallengeCalculator.dailyLimit(req.budgetTotal(), durationDays);
        Challenge challenge = Challenge.builder()
                .userId(userId)
                .durationDays(durationDays)
                .startDate(startDate)
                .budgetTotal(req.budgetTotal())
                .dailyLimit(dailyLimit)
                .fixedDay(req.fixedDay())
                .build();
        try {
            challengeRepository.save(challenge);
        } catch (DataIntegrityViolationException e) {
            // IDENTITY INSERT가 즉시 실행되므로 조건부 UNIQUE 위반을 이 범위에서 409로 변환할 수 있다.
            throw alreadyInProgressOr(e);
        }
        transferSameDayRecord(userId, challenge);
        return CreateChallengeResponse.from(challenge);
    }

    /**
     * 저장 실패가 "진행 중 챌린지는 유저당 1건" 위반이면 409로 바꾸고, 그 외 제약 위반은 원래 예외 그대로 돌려준다.
     * 무결성 위반이라고 다 같은 원인이 아니라서 이름으로 가른다 — 뭉개면 앞으로 추가될 제약 위반까지
     * "이미 진행 중입니다"로 보여 서버 결함이 정상 응답으로 감춰진다.
     * 같은지가 아니라 포함인지를 보는 이유: H2는 스키마·인덱스 이름까지 붙여 돌려준다(UserRestService에서 실측).
     */
    private static RuntimeException alreadyInProgressOr(DataIntegrityViolationException e) {
        boolean alreadyInProgress = e.getCause() instanceof ConstraintViolationException cause
                && cause.getConstraintName() != null
                && cause.getConstraintName().toUpperCase(Locale.ROOT)
                        .contains(Challenge.ACTIVE_USER_UNIQUE.toUpperCase(Locale.ROOT));
        return alreadyInProgress ? new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS) : e;
    }

    private void transferSameDayRecord(Long userId, Challenge challenge) {
        challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, List.of(ChallengeStatus.FAIL))
                .filter(previous -> previous.getEndReason() == EndReason.GIVEN_UP)
                .filter(previous -> challenge.getStartDate().equals(previous.getInactiveFrom()))
                .flatMap(previous -> challengeDayRepository.findByChallenge_IdAndDayDate(
                        previous.getId(), challenge.getStartDate()))
                .ifPresent(day -> day.transferTo(
                        challenge,
                        ChallengeCalculator.judge(day.getSpentAmount(), challenge.getDailyLimit()),
                        challenge.getDailyLimit()));
    }

    /** 직전 날짜 고정 챌린지의 목표를 채워, 오늘 시작해 다음 고정일 전날 끝나는 다음 챌린지 초안을 반환한다. */
    @Transactional
    public NextFixedDateChallengeResponse getNextFixedDateChallenge(Long userId) {
        userOperationLock.lock(userId);
        finalizeDueInProgress(userId);
        LocalDate today = LocalDate.now(clock);
        Challenge latest = challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .filter(Challenge::isFixedDate)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.FIXED_DATE_SETTING_NOT_FOUND));
        if (!today.isAfter(latest.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.FIXED_DATE_NOT_DUE);
        }
        // 늦게 열어도 주기는 고정일에 정렬된다 — 초안의 시작일은 오늘이 아니라 이번 주기의 고정일이다.
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.containing(today, latest.getFixedDay());
        int dailyLimit = ChallengeCalculator.dailyLimit(latest.getBudgetTotal(), plan.durationDays());
        return new NextFixedDateChallengeResponse(latest.getId(), latest.getFixedDay(),
                plan.startDate(), plan.endDate(), plan.durationDays(), latest.getBudgetTotal(), dailyLimit);
    }

    /**
     * 직전 챌린지의 고정일·목표를 이어받아 이번 주기의 챌린지를 생성하고 활성 휴식은 오늘 종료한다.
     * 고정일보다 늦게 입장해도 주기는 고정일에 정렬되므로, 지나간 날은 그대로 미입력으로 남아
     * 기존 미입력 규칙의 판정 대상이 된다.
     */
    @Transactional
    public CreateChallengeResponse startFixedDate(Long userId, StartFixedDateChallengeRequest req) {
        userOperationLock.lock(userId);
        finalizeDueInProgress(userId);
        LocalDate today = LocalDate.now(clock);
        Optional<Challenge> active = challengeRepository.findInProgress(userId);
        if (active.isPresent()) {
            Challenge current = active.get();
            if (isStartedForCycleOf(userId, current, today, req)) {
                return CreateChallengeResponse.from(current);
            }
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        Challenge latest = challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .filter(Challenge::isFixedDate)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.FIXED_DATE_SETTING_NOT_FOUND));
        // 생성 직후 자동 취소된 챌린지는 진행 중으로 남지 않으므로 최신 챌린지 쪽에서도 멱등을 판정한다.
        if (isStartedForCycleOf(userId, latest, today, req)) {
            return CreateChallengeResponse.from(latest);
        }
        if (!latest.getId().equals(req.sourceChallengeId())) {
            throw new CustomException(ChallengeErrorCode.FIXED_DATE_SOURCE_STALE);
        }
        if (!today.isAfter(latest.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.FIXED_DATE_NOT_DUE);
        }
        userRestRepository.findActiveOn(userId, today).ifPresent(rest -> rest.resume(today));
        FixedDateChallengeCycle.Plan plan = FixedDateChallengeCycle.containing(today, latest.getFixedDay());
        int dailyLimit = ChallengeCalculator.dailyLimit(latest.getBudgetTotal(), plan.durationDays());
        Challenge challenge = Challenge.builder()
                .userId(userId)
                .durationDays(plan.durationDays())
                .startDate(plan.startDate())
                .activatedDate(today) // 주기 시작일과 실제 입장일이 다를 수 있다.
                .budgetTotal(latest.getBudgetTotal())
                .dailyLimit(dailyLimit)
                .fixedDay(latest.getFixedDay())
                .build();
        try {
            challengeRepository.save(challenge);
        } catch (DataIntegrityViolationException e) {
            // IDENTITY INSERT가 즉시 실행되므로 조건부 UNIQUE 위반을 이 범위에서 409로 변환할 수 있다.
            throw alreadyInProgressOr(e);
        }
        // 이미 3일이 지나 입장했다면 생성과 동시에 자동 취소된다 — 조회를 기다리지 않고 여기서 확정한다.
        evaluateExpenseInputState(userId, challenge, today);
        return CreateChallengeResponse.from(challenge);
    }

    /**
     * 이번 고정 주기의 챌린지가 이 요청으로 이미 만들어졌는지 — 같은 도래에 대한 중복 요청을 멱등하게 만든다.
     * 주기 시작일이 같고 바로 앞 챌린지가 요청이 지목한 직전 챌린지면 같은 도래로 본다.
     */
    private boolean isStartedForCycleOf(Long userId, Challenge candidate, LocalDate today,
                                        StartFixedDateChallengeRequest req) {
        return candidate.isFixedDate()
                && candidate.getStartDate().equals(
                        FixedDateChallengeCycle.containing(today, candidate.getFixedDay()).startDate())
                && challengeRepository
                        .findFirstByUserIdAndIdNotOrderByCreatedAtDescIdDesc(userId, candidate.getId())
                        .map(previous -> previous.getId().equals(req.sourceChallengeId()))
                        .orElse(false);
    }

    /** 날짜를 생략한 조회는 오늘을 선택 날짜로 사용한다. */
    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId) {
        userOperationLock.lock(userId);
        // 조회만으로 기간 종료 결과를 확정하면 최종 종료 전 지출 수정 구간이 사라지므로 hasActiveChallenge를 사용하지 않는다.
        LocalDate today = LocalDate.now(clock);
        Optional<Challenge> challenge = challengeRepository.findActiveOnDate(userId, today);
        if (challenge.isEmpty()) {
            return CurrentChallengeResponse.empty();
        }
        Challenge c = challenge.get();

        ExpenseInputState expenseInputState = evaluateExpenseInputState(userId, c, today);
        DailyLimitTimeline limits = timelineOf(c);
        return buildChallengeResponse(userId, c, today, c.getDailyLimit(), limits,
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

        Optional<Challenge> challenge = challengeRepository.findActiveOnDate(userId, date);
        if (challenge.isEmpty()) {
            return CurrentChallengeResponse.empty();
        }

        Challenge c = challenge.get();
        List<ChallengeAdjustment> adjustments = challengeAdjustmentRepository
                .findByChallenge_IdOrderByEffectiveDateAscIdAsc(c.getId());
        DailyLimitTimeline limits = DailyLimitTimeline.of(c, adjustments);
        int dailyLimit = limits.on(date);
        int usedAdjustmentCount = (int) adjustments.stream()
                .filter(adjustment -> !adjustment.getEffectiveDate().isAfter(date))
                .count();

        return buildChallengeResponse(userId, c, date, dailyLimit, limits,
                ExpenseInputState.NORMAL, usedAdjustmentCount);
    }

    private CurrentChallengeResponse buildChallengeResponse(
            Long userId,
            Challenge c,
            LocalDate selectedDate,
            int dailyLimit,
            DailyLimitTimeline limits,
            ExpenseInputState expenseInputState,
            int usedAdjustmentCount) {
        LocalDate aggregationEndDate = selectedDate.isAfter(c.getEndDate()) ? c.getEndDate() : selectedDate;
        Map<LocalDate, Integer> spentByDate = aggregationEndDate.isBefore(c.getStartDate())
                ? Map.of()
                : expenseService.getDailySpending(userId, c.getStartDate(), aggregationEndDate);
        ChallengeSummary summary = aggregationEndDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0)
                : ChallengeCalculator.summarizeThrough(spentByDate, limits, c.getStartDate(), aggregationEndDate);

        int spent = expenseService.getDaySpending(userId, selectedDate).totalAmount();
        double usageRate = ChallengeCalculator.usageRate(spent, dailyLimit);
        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), dailyLimit, c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, selectedDate), remainingDays(c, selectedDate),
                summary.successDays(), summary.overDays(),
                ChallengeCalculator.currentStreakAsOf(spentByDate, limits, c.getStartDate(), aggregationEndDate),
                summary.savedAmount());
        var consumption = new CurrentChallengeResponse.Consumption(
                spent, dailyLimit - spent, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), AlertLevel.of(usageRate));

        var adjustment = new CurrentChallengeResponse.Adjustment(
                usedAdjustmentCount, ChallengeCalculator.maxAdjustmentCount(c.getDurationDays()));

        return CurrentChallengeResponse.forChallenge(
                view, progress, consumption, List.of(), expenseInputState, adjustment);
    }

    /** 8일 이상 챌린지에서 진행 중에는 어제까지, 기간 종료 후에는 종료일까지 마지막 연속 미입력을 판정한다. */
    private ExpenseInputState evaluateExpenseInputState(Long userId, Challenge challenge, LocalDate today) {
        if (challenge.getEffectiveDurationDays() < Challenge.AUTO_CANCEL_MIN_DURATION_DAYS
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
        if (challengeRangeInMonth.isEmpty()) {
            return new CalendarResponse(challengeId, year, month, List.of());
        }

        Map<LocalDate, Integer> spentByDate = expenseService.getDailySpending(
                userId, challengeRangeInMonth.start(), challengeRangeInMonth.end());
        List<LocalDate> noSpendDates = noSpendDayRepository
                .findByUser_IdAndRecordDateBetween(userId, challengeRangeInMonth.start(), challengeRangeInMonth.end())
                .stream().map(NoSpendDay::getRecordDate).toList();
        DailyLimitTimeline limits = timelineOf(c);

        // 지출 있는 날 ∪ 오늘은 안 썼어요 선언된 날 = 캘린더에 실릴 기록 있는 날
        Set<LocalDate> recordedDates = new TreeSet<>(spentByDate.keySet());
        recordedDates.addAll(noSpendDates);
        List<CalendarResponse.DayView> days = recordedDates.stream()
                .map(date -> {
                    int spent = spentByDate.getOrDefault(date, 0);
                    return new CalendarResponse.DayView(date, ChallengeCalculator.judge(spent, limits.on(date)), spent);
                })
                .toList();
        return new CalendarResponse(challengeId, year, month, days);
    }

    /** 기간 종료 후 3일 미입력 무효를 먼저 판정하고, 무효가 아니면 금액으로 결과를 확정한다. */
    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        userOperationLock.lock(userId);
        Challenge c = loadOwned(userId, challengeId);
        LocalDate aggregationEndDate = aggregationEndDate(c);
        Map<LocalDate, Integer> spentByDate = aggregationEndDate.isBefore(c.getStartDate())
                ? Map.of()
                : expenseService.getDailySpending(userId, c.getStartDate(), aggregationEndDate);
        ChallengeSummary s = aggregationEndDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0)
                : ChallengeCalculator.summarizeThrough(spentByDate, timelineOf(c), c.getStartDate(), aggregationEndDate);

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
        List<Challenge> ended = challengeRepository.findCompletedByUserIdOrderByEndDateDescIdDesc(userId);
        if (ended.isEmpty()) {
            return new ChallengeHistoryResponse(List.of());
        }

        Map<Long, ChallengeSummary> summariesByChallengeId = summarizeCompletedChallenges(userId, ended);
        List<ChallengeHistoryResponse.Item> items = ended.stream()
                .map(c -> {
                    ChallengeSummary s = summariesByChallengeId.get(c.getId());
                    return ChallengeHistoryResponse.Item.of(c, s.actualSpent(), s.savedAmount());
                })
                .toList();
        return new ChallengeHistoryResponse(items);
    }

    private Map<Long, ChallengeSummary> summarizeCompletedChallenges(Long userId, List<Challenge> completed) {
        if (completed.isEmpty()) {
            return Map.of();
        }
        List<Long> completedIds = completed.stream().map(Challenge::getId).toList();
        Map<Long, List<ChallengeAdjustment>> adjustmentsByChallengeId = challengeAdjustmentRepository
                .findByChallenge_IdInOrderByEffectiveDateAscIdAsc(completedIds)
                .stream()
                .collect(Collectors.groupingBy(a -> a.getChallenge().getId()));
        LocalDate minStart = completed.stream().map(Challenge::getStartDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate maxEnd = completed.stream().map(this::aggregationEndDate).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, Integer> spentByDate = expenseService.getDailySpending(userId, minStart, maxEnd);

        return completed.stream().collect(Collectors.toMap(
                Challenge::getId,
                challenge -> ChallengeCalculator.summarizeThrough(
                        spentByDate,
                        DailyLimitTimeline.of(
                                challenge, adjustmentsByChallengeId.getOrDefault(challenge.getId(), List.of())),
                        challenge.getStartDate(), aggregationEndDate(challenge))));
    }

    @Transactional
    public RecommendationResponse getRecommendation(Long userId) {
        userOperationLock.lock(userId);
        finalizeDueInProgress(userId);
        Challenge last = challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL, ChallengeStatus.VOID))
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ENDED_CHALLENGE));

        LocalDate aggregationEndDate = aggregationEndDate(last);
        Map<LocalDate, Integer> spentByDate = aggregationEndDate.isBefore(last.getStartDate())
                ? Map.of()
                : expenseService.getDailySpending(userId, last.getStartDate(), aggregationEndDate);
        ChallengeSummary s = aggregationEndDate.isBefore(last.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0)
                : ChallengeCalculator.summarizeThrough(spentByDate, timelineOf(last), last.getStartDate(), aggregationEndDate);
        int recommendedDurationDays = ChallengeCalculator.recommendedDurationDays(last.getDurationDays());
        int recommendedBudgetTotal = ChallengeCalculator.recommendedBudgetTotal(
                last.getStatus(), last.getEndReason(), last.getBudgetTotal(), s.actualSpent());

        return new RecommendationResponse(
                recommendationMessage(
                        last.getStatus(),
                        last.getEndReason(),
                        last.getBudgetTotal(),
                        s.actualSpent(),
                        recommendedDurationDays,
                        recommendedBudgetTotal));
    }

    static String recommendationMessage(ChallengeStatus status, EndReason endReason,
                                        int budgetTotal, int actualSpent,
                                        int recommendedDurationDays, int recommendedBudgetTotal) {
        if (endReason == EndReason.GIVEN_UP) {
            return "이번 챌린지는 중도 포기로 끝났어요." + recommendationPlan(
                    budgetTotal, recommendedDurationDays, recommendedBudgetTotal);
        }
        if (endReason == EndReason.MISSING_DAILY_INPUT) {
            return "지출 미입력으로 이번 챌린지가 자동 취소됐어요." + recommendationPlan(
                    budgetTotal, recommendedDurationDays, recommendedBudgetTotal);
        }

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
        c.giveUp(LocalDate.now(clock));
        return GiveUpResponse.from(c);
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
    public void finalizeDueChallenge(Long userId, Long challengeId, LocalDate judgmentDate) {
        userOperationLock.lock(userId);
        challengeRepository.findById(challengeId)
                .filter(challenge -> challenge.isOwnedBy(userId))
                .ifPresent(challenge -> applyDueFinalization(userId, challenge, judgmentDate));
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

    private void applyDueFinalization(Long userId, Challenge c, LocalDate judgmentDate) {
        if (!c.isInProgress()) {
            return;
        }
        if (evaluateExpenseInputState(userId, c, judgmentDate) == ExpenseInputState.AUTO_CANCELLED) {
            return;
        }
        if (!judgmentDate.isAfter(c.getEndDate())) {
            return;
        }
        Map<LocalDate, Integer> spentByDate = expenseService.getDailySpending(userId, c.getStartDate(), c.getEndDate());
        ChallengeSummary s = ChallengeCalculator.summarizeThrough(
                spentByDate, timelineOf(c), c.getStartDate(), c.getEndDate());
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
        return Objects.requireNonNull(
                challenge.getInactiveFrom(), "종료 사유가 있는 챌린지의 inactiveFrom은 필수입니다.")
                .minusDays(1);
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
