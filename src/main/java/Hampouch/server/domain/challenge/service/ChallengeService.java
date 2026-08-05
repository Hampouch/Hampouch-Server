package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
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
@Transactional(readOnly = true) // 쓰기가 필요한 메서드만 개별 @Transactional로 덮어쓴다
public class ChallengeService {

    /** 한도 조정 최대 횟수(챌린지당). */
    private static final int MAX_ADJUSTMENT_COUNT = 2;
    private static final int AUTO_CANCEL_MIN_DURATION_DAYS = 8;
    private static final int MISSING_INPUT_WARNING_DAYS = 2;
    private static final int MISSING_INPUT_CANCEL_DAYS = 3;

    private final ChallengeRepository challengeRepository;
    private final ChallengeDayRepository challengeDayRepository;
    private final ExpenseService expenseService;
    // UserRestService가 아니라 리포지토리를 주입한다 — 그쪽이 이 서비스를 주입받고 있어서 서로 주입하면 순환으로 기동이 실패한다.
    private final UserRestRepository userRestRepository;
    // "지금"의 단일 출처(Asia/Seoul). 인자 없는 LocalDate.now()는 UTC 배포에서 한국 새벽에 날짜가 하루 어긋난다.
    private final Clock clock;

    /** 챌린지 생성. 동시 진행 1개 가정 → 진행 중 존재 시 409. 휴식 중이었다면 자동 종료 후 생성(휴식 명세 §1 배타 규칙). */
    @Transactional
    public CreateChallengeResponse create(Long userId, CreateChallengeRequest req) {
        // 만료 미확정 챌린지를 먼저 확정하지 않으면 저장된 IN_PROGRESS 상태가 새 생성을 잘못 막는다.
        if (finalizeExpiredAndCheckActiveChallenge(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        // 배타 규칙(#8): 생성 자체가 복귀 의사라 활성 휴식을 오늘로 닫는다(챌린지 시작일이 미래여도 종료일은 오늘 — 명세 문언).
        // 안 닫으면 챌린지가 끝나는 순간 옛 휴식이 findActiveOn에 다시 잡혀 낡은 날짜의 휴식기 홈이 되살아난다.
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
            // 위 존재 검사를 동시에 통과한 경쟁 요청이 DB 유니크(uq_challenge_active_user)에 걸린 경우 — 같은 409로 변환.
            // id가 IDENTITY라 save()가 즉시 INSERT를 날리므로 위반이 커밋까지 밀리지 않고 이 자리에서 잡힌다.
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        return CreateChallengeResponse.from(challenge);
    }

    /** 진행 중 챌린지 + 현황(챌린지 모드). 휴식 중이면 휴식기 홈(휴식 모드, #8) — 둘 다 아닐 때만 404. */
    @Transactional
    public CurrentChallengeResponse getCurrent(Long userId) {
        // 진행 중 챌린지가 있으면 무조건 그쪽 우선(휴식 명세 §1) — 활성 휴식과 공존하는 꼬인 데이터에서도 챌린지 홈이 이긴다.
        // 여기만 hasActiveChallenge를 안 쓰는 건 의도적이다 — 기간이 끝나도 유저가 종료 팝업에서 [챌린지 종료]를
        // 누르기 전까지는 아직 끝난 게 아닌데(그 구간에 지출을 마저 입력한다), hasActiveChallenge는 조회만으로
        // 만료분을 확정해 버려 그 구간이 사라진다. 확정을 유저 액션에 묶는 건 #50 몫이고, 여기선 앞당겨 끝내지만 않는다.
        Optional<Challenge> inProgress = challengeRepository.findInProgress(userId);
        if (inProgress.isEmpty()) {
            return restHomeOrNotFound(userId);
        }
        Challenge c = inProgress.get();

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        LocalDate today = LocalDate.now(clock);
        ExpenseInputState expenseInputState = evaluateExpenseInputState(userId, c, today);

        // TODO(령준 지출 연동): todaySpent 출처 교체(연동 전엔 POST /days로 받은 값).
        int dailyLimit = c.getDailyLimit();
        // 금액과 "오늘 기록이 있는가"(lastJudgedDate 분기) 두 용도라 Optional째 들고 있는다
        Optional<ChallengeDay> todayRow = challengeDayRepository.findByChallenge_IdAndDayDate(c.getId(), today);
        int todaySpent = todayRow.map(ChallengeDay::getSpentAmount).orElse(0);
        double usageRate = ChallengeCalculator.usageRate(todaySpent, dailyLimit);
        AlertLevel alertLevel = AlertLevel.of(usageRate);

        // 집계·스트릭은 판정 완료 구간(시작일 ~ lastJudgedDate)까지만 계산 — 날짜 규칙은 아래 lastJudgedDate() 참조
        LocalDate lastJudgedDate = lastJudgedDate(c, today, todayRow.isPresent());
        ChallengeSummary s = lastJudgedDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0) // 시작 첫날 기록 전 — 아직 판정된 날이 없음
                : ChallengeCalculator.summarizeForResult(days, dailyLimit, c.getStartDate(), lastJudgedDate);

        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), c.getDailyLimit(), c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, today), remainingDays(c, today),
                s.successDays(), s.overDays(),
                ChallengeCalculator.currentStreakAsOf(days, c.getStartDate(), lastJudgedDate), s.savedAmount());
        int todayRemaining = dailyLimit - todaySpent; // 초과 시 음수 그대로(명세 확정 — 클램프 안 함)
        var consumption = new CurrentChallengeResponse.Consumption(
                todaySpent, todayRemaining, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), alertLevel);

        // 경고 카드는 오늘 사용률(alertLevel)과 무관한 별개 신호라 공통 게이트가 없다 — 어제까지 3연속 초과면 오늘 사용률이 낮아도 뜬다.
        // 미기록일은 0원=성공으로 채우므로 하루 건너뛰면 연속이 끊겨 카드가 사라진다.
        // TODO(#52): WEAK_CATEGORY_ALERT 구현 — 령준 카테고리별 집계가 나온 뒤.
        List<WarningCard> warningCards = new ArrayList<>();
        if (c.isInProgress() && ChallengeCalculator.isGoalTooTight(days, c.getStartDate(), lastJudgedDate)) {
            warningCards.add(WarningCard.GOAL_TOO_TIGHT);
        }

        // TODO(#7): 조정 API가 생기면 사용 횟수 0 하드코딩을 challenge_adjustment 이력 행 수로 교체.
        var adjustment = new CurrentChallengeResponse.Adjustment(0, MAX_ADJUSTMENT_COUNT);

        return CurrentChallengeResponse.forChallenge(
                view, progress, consumption, warningCards, expenseInputState, adjustment);
    }

    /** 오늘을 제외한 최근 완료일을 거꾸로 확인한다. 7일 챌린지는 자동 취소 대상이 아니다. */
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
            if (expenseService.getDaySpending(userId, dateToCheck).hasRecord()) {
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

    /**
     * 휴식기 홈(#8, 휴식 명세 §3) — 활성 휴식이 있으면 404 대신 200으로 challenge:null + rest + keptRecords를
     * 내려 홈이 휴식 화면을 그리게 하고, 휴식마저 없으면 기존 정책 그대로 404.
     */
    private CurrentChallengeResponse restHomeOrNotFound(Long userId) {
        UserRest rest = userRestRepository.findActiveOn(userId, LocalDate.now(clock))
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ACTIVE_CHALLENGE));
        return CurrentChallengeResponse.forRest(CurrentChallengeResponse.RestView.from(rest), keptRecords(userId));
    }

    /**
     * 보관 중인 내 기록 = 직전 종료 챌린지의 절약·최고 연속(휴식 명세 §3). 집계는 결과·히스토리와 같은
     * summarizeForResult를 써야 한다 — 휴식기 홈의 숫자가 직전 결과 화면과 어긋나면 안 되기 때문.
     * ⚠️ "직전 하나"는 잠정이다 — 시안 라벨("누적 절약 금액")이 역대 누계로도 읽혀 PM 확인 대기 중.
     * "직전"을 endDate가 아닌 생성순으로 고르는 이유는 리포지토리 쿼리 주석 참조(포기 챌린지의 미래 endDate 함정).
     * 종료 챌린지가 없으면 0/0(잠정) — 챌린지 없이 휴식을 연 유저에게 실제로 생기는 상태다.
     */
    private CurrentChallengeResponse.KeptRecords keptRecords(Long userId) {
        return challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL))
                .map(prev -> {
                    ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                            challengeDayRepository.findByChallenge_Id(prev.getId()),
                            prev.getDailyLimit(), prev.getStartDate(), prev.getEndDate());
                    return new CurrentChallengeResponse.KeptRecords(s.savedAmount(), s.maxStreak());
                })
                .orElse(new CurrentChallengeResponse.KeptRecords(0, 0));
    }

    /**
     * 캘린더 — 유저 전체가 아니라 "이 챌린지의" 달력이라 요청한 달 ∩ 챌린지 기간만 조회한다(기록이 그 안에만 존재).
     */
    public CalendarResponse getCalendar(Long userId, Long challengeId, int year, int month) {
        Challenge c = loadOwned(userId, challengeId); // 존재·소유 먼저(404/403) → 그 다음 파라미터 검증
        if (month < 1 || month > 12) {
            // 13월은 아래 LocalDate.of가 DateTimeException을 던져 500이 되므로 여기서 400으로 컷
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }
        if (year < 1 || year > 9999) {
            // LocalDate.of는 ±999,999,999까지 받지만 MySQL DATE 범위(1000~9999)를 넘으면 쿼리 단계에서 500이 된다
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }

        DateRange challengeRangeInMonth = DateRange.ofMonth(year, month)
                .intersect(new DateRange(c.getStartDate(), c.getEndDate()));

        // 겹치지 않는 달(기간 밖)을 보는 건 달 넘기기의 정상 경로라 400이 아니라 빈 캘린더(200)로 응답
        List<CalendarResponse.DayView> days = challengeRangeInMonth.isEmpty()
                ? List.of()
                : challengeDayRepository.findByChallenge_IdAndDayDateBetween(
                                challengeId, challengeRangeInMonth.start(), challengeRangeInMonth.end()).stream()
                        .sorted(Comparator.comparing(ChallengeDay::getDayDate))
                        .map(d -> new CalendarResponse.DayView(d.getDayDate(), d.getStatus(), d.getSpentAmount()))
                        .toList();
        return new CalendarResponse(challengeId, year, month, days);
    }

    /** 종료 결과. 진행 중이고 아직 end_date 전이면 409, end_date 경과면 최초 계산 시 status 확정 저장(이후 지출 수정 시 upsertDay가 재계산). */
    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(challengeId);

        if (c.isInProgress()) {
            LocalDate today = LocalDate.now(clock);
            if (!today.isAfter(c.getEndDate())) {
                throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
            }
            // 기록 0건이어도 그대로 확정(0714 PM: 성공 처리) — 미입력일=0원=성공 규칙이라 전일 미입력이면 SUCCESS.
            c.applyResult(ChallengeCalculator.resultStatus(days)); // end_date 경과 → 최초 계산 시 저장(배치 없음)
        }

        // 결과는 기간 전체 기준 — 미입력일 = 0원 SUCCESS 간주(0630 확정, 명세 §4)
        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, c.getDailyLimit(), c.getStartDate(), c.getEndDate());
        var period = ResultResponse.Period.from(c);
        var summary = new ResultResponse.Summary(
                s.successDays(), s.overDays(), s.savedAmount(), s.overAmount(),
                s.maxStreak(), c.getBudgetTotal(), s.actualSpent());
        // categoryBreakdown / emotionBreakdown 은 령준 지출(EXPENSE) 의존 → 연동 확정까지 빈 배열
        return new ResultResponse(c.getId(), c.getStatus(), period, summary, List.of(), List.of());
    }

    /** 일별 지출 수신 → 그날 판정 upsert. */
    @Transactional
    public DayUpsertResponse upsertDay(Long userId, Long challengeId, DayUpsertRequest req) {
        Challenge c = loadOwned(userId, challengeId);
        if (req.date().isBefore(c.getStartDate()) || req.date().isAfter(c.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.DAY_OUT_OF_RANGE);
        }
        int dailyLimit = c.getDailyLimit();
        DayStatus status = ChallengeCalculator.judge(req.spentAmount(), dailyLimit);

        ChallengeDay day = challengeDayRepository.findByChallenge_IdAndDayDate(challengeId, req.date())
                .map(existing -> {
                    existing.update(req.spentAmount(), status);
                    return existing;
                })
                .orElseGet(() -> challengeDayRepository.save(
                        ChallengeDay.of(c, req.date(), req.spentAmount(), status)));

        // 기록으로 계산된 종료 상태만 다시 계산한다. 중도 포기와 미입력 자동 취소는 지출을 고쳐도 되살리지 않는다.
        if (!c.isInProgress() && c.getEndReason() == null) {
            c.applyResult(ChallengeCalculator.resultStatus(challengeDayRepository.findByChallenge_Id(challengeId)));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    /**
     * 지난 챌린지 리스트(#4) — 종료된 것만, 최근 종료가 먼저. 없으면 빈 리스트(에러 아님).
     * 조회인데 쓰기 @Transactional인 이유: 확정이 lazy라 결과 화면을 안 연 챌린지는 status가 IN_PROGRESS로
     * 남아 있고, 그대로 조회하면 방금 끝난 챌린지가 히스토리에서 빠진다.
     */
    @Transactional
    public ChallengeHistoryResponse getHistory(Long userId) {
        finalizeExpiredInProgress(userId);
        // 방금 확정한 상태는 커밋 전이지만 JPQL 실행 직전 자동 플러시라 아래 조회에 잡힌다
        List<Challenge> ended = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL));
        if (ended.isEmpty()) {
            return new ChallengeHistoryResponse(List.of());
        }

        // 챌린지마다 따로 조회하면 N+1이라 in절 한 방으로 받아 id별로 나눈다.
        // 기록 0건인 챌린지는 키 자체가 안 생기므로 꺼낼 때 getOrDefault.
        Map<Long, List<ChallengeDay>> daysByChallengeId = challengeDayRepository
                .findByChallenge_IdIn(ended.stream().map(Challenge::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(d -> d.getChallenge().getId()));

        List<ChallengeHistoryResponse.Item> items = ended.stream()
                .map(c -> {
                    // 금액 요약은 결과 화면(§4)과 같은 규칙으로 조회 시 계산 — 미입력일 = 0원 지출 = 성공 간주(0630 확정)
                    ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                            daysByChallengeId.getOrDefault(c.getId(), List.of()),
                            c.getDailyLimit(), c.getStartDate(), c.getEndDate());
                    return ChallengeHistoryResponse.Item.of(c, s.actualSpent(), s.savedAmount());
                })
                .toList();
        return new ChallengeHistoryResponse(items);
    }

    /**
     * 중도 포기 — IN_PROGRESS를 유저 선언 FAIL로 즉시 확정. 만료됐지만 아직 확정 안 된 챌린지도 409로 막는다:
     * 안 막으면 기간을 다 채워 SUCCESS여야 할 결과가 FAIL로 굳고, GIVEN_UP은 재계산 제외라 되돌릴 수 없다.
     * 기간 마지막 날까지는 만료가 아니라 포기할 수 있다(getResult의 409 경계와 동일).
     *
     * 그 만료분을 여기서 확정하지 않고 만료 여부만 읽는 이유(나연 리뷰 반영): 상태를 바꾼 뒤 409를 던지면
     * 예외가 RuntimeException이라 확정까지 롤백돼, 응답은 409인데 DB는 IN_PROGRESS로 남는다.
     */
    @Transactional
    public GiveUpResponse giveUp(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        if (!c.isInProgress() || isExpired(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
        }
        c.giveUp();
        return GiveUpResponse.from(c);
    }

    /**
     * 집중 카테고리 수정 — 진행 중일 때만 허용하는 것은 ⚠️잠정이다(수정 화면이 시안에 없어 명세 공백).
     * 끝난 챌린지의 카테고리는 그 결과·기록을 설명하는 과거 값이라 잠갔고, 만료됐지만 아직 미확정인
     * 챌린지까지 막는 경계는 중도 포기와 같아 조건식을 공유한다.
     */
    @Transactional
    public FocusCategoriesResponse updateFocusCategories(Long userId, Long challengeId, FocusCategoriesRequest req) {
        Challenge c = loadOwned(userId, challengeId);
        if (!c.isInProgress() || isExpired(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
        }
        c.replaceWeakCategories(req.categories());
        return FocusCategoriesResponse.from(c);
    }

    /**
     * 진행 중 챌린지가 "실제로" 있는가 — 만료 미확정분을 먼저 확정한 뒤 판단한다. 생성·휴식 시작의 409
     * 게이트가 함께 쓰는 창구다. **"진행 중인가"는 existsInProgress 직접 호출 말고 이 창구로 물을 것** —
     * 저장 status만 보면 결과 화면을 안 연 만료 챌린지가 생성·휴식을 잘못 막는다. 확정 저장 때문에 쓰기 트랜잭션.
     */
    @Transactional
    public boolean hasActiveChallenge(Long userId) {
        return finalizeExpiredAndCheckActiveChallenge(userId);
    }

    private boolean finalizeExpiredAndCheckActiveChallenge(Long userId) {
        finalizeExpiredInProgress(userId);
        return challengeRepository.existsInProgress(userId);
    }

    /**
     * 기간이 끝났는데 아직 확정 전인 챌린지를 확정 — 판정을 돌리는 배치가 없어서 endDate가 지나도 다음
     * 조회가 올 때까지 행은 IN_PROGRESS로 남는다(lazy 확정). 재료(일별 기록)가 이미 다 있어 늦게 계산해도 답은 같다.
     * 확정을 먼저 실행하는 경로는 결과·히스토리 조회와 생성·휴식 시작 게이트다 — 홈 현황(getCurrent)만 일부러 빠져 있다.
     */
    private void finalizeExpiredInProgress(Long userId) {
        challengeRepository.findInProgress(userId).ifPresent(this::finalizeIfExpired);
    }

    /**
     * 기간이 끝났는데 확정 전이면 그 자리에서 확정 — 히스토리·포기가 같은 규칙을 쓰도록 뽑아낸 단일 출처.
     * save가 없는 건 더티 체킹이 커밋 때 UPDATE를 내보내기 때문(upsertDay와 동일).
     */
    private void finalizeIfExpired(Challenge c) {
        if (c.isInProgress() && isExpired(c)) {
            c.applyResult(ChallengeCalculator.resultStatus(
                    challengeDayRepository.findByChallenge_Id(c.getId())));
        }
    }

    /**
     * 기간이 지났는지만 판정하고 상태는 안 바꾼다 — 포기가 만료 챌린지를 롤백 없이 걸러내는 데 쓴다.
     * 기간 마지막 날은 아직 만료가 아니다(getResult·finalizeIfExpired의 경계와 동일).
     */
    private boolean isExpired(Challenge c) {
        return LocalDate.now(clock).isAfter(c.getEndDate());
    }

    private Challenge loadOwned(Long userId, Long challengeId) {
        Challenge c = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwnedBy(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_FORBIDDEN);
        }
        return c;
    }

    /**
     * 채점이 끝난 마지막 날 — 집계와 스트릭은 여기까지만 센다. endDate(고정 종료일)와 달리 오늘을 따라
     * 움직이는 커서다(보통 어제, 오늘 기록을 보냈으면 오늘, 끝난 챌린지는 endDate에서 멈춤).
     * 오늘을 기록 있을 때만 포함하는 이유: 안 그러면 매일 아침 오늘이 미리 '성공'으로 집계된다(미입력일=0원=성공 규칙 탓).
     */
    private static LocalDate lastJudgedDate(Challenge c, LocalDate today, boolean todayRecorded) {
        LocalDate last = todayRecorded ? today : today.minusDays(1);
        return last.isAfter(c.getEndDate()) ? c.getEndDate() : last; // 종료일을 넘지 않게 — 둘 중 이른 날
    }

    /** 경과 일수 (시작일 당일 = 1, 기간 내로 클램프). */
    private int elapsedDays(Challenge c, LocalDate today) {
        if (today.isBefore(c.getStartDate())) {
            return 0;
        }
        LocalDate last = today.isAfter(c.getEndDate()) ? c.getEndDate() : today;
        return (int) (ChronoUnit.DAYS.between(c.getStartDate(), last) + 1); // 시작일 당일이 0이라 +1 해서 "1일차"
    }

    /** 남은 일수 (오늘 제외, 종료 후 0). */
    private int remainingDays(Challenge c, LocalDate today) {
        if (today.isAfter(c.getEndDate())) {
            return 0;
        }
        LocalDate base = today.isBefore(c.getStartDate()) ? c.getStartDate() : today;
        return (int) ChronoUnit.DAYS.between(base, c.getEndDate());
    }
}
