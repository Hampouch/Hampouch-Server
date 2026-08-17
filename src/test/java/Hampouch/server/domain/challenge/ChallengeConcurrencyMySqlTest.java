package Hampouch.server.domain.challenge;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeAdjustment;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.EndReason;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.expense.dto.ExpenseUpdateRequest;
import Hampouch.server.domain.expense.dto.NoSpendRecordRequest;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.rest.dto.*;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.rest.service.UserRestService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.BaseErrorCode;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.RestErrorCode;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlContainerTest
@Import(ChallengeConcurrencyMySqlTest.PausingUserLockConfig.class)
class ChallengeConcurrencyMySqlTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    ChallengeService challengeService;
    @Autowired
    UserRestService userRestService;
    @Autowired
    ExpenseService expenseService;
    @Autowired
    ChallengeRepository challengeRepository;
    @Autowired
    ChallengeAdjustmentRepository challengeAdjustmentRepository;
    @Autowired
    UserRestRepository userRestRepository;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    PausingUserOperationLock pausingUserOperationLock;

    @Test
    @DisplayName("동일 사용자 진행 중 챌린지 UNIQUE는 실제 MySQL 동시 INSERT 중 하나를 거절한다")
    void activeChallengeUniqueRejectsConcurrentInsert() throws Exception {
        User user = newUser("challenge-unique");
        LocalDate today = today();

        List<Outcome<Challenge>> outcomes = race(
                () -> challengeRepository.saveAndFlush(challengeOf(user.getId(), 7, today, 70000)),
                () -> challengeRepository.saveAndFlush(challengeOf(user.getId(), 7, today, 84000)));

        assertConstraintRace(outcomes);
        assertThat(challengeRepository.findAll().stream()
                .filter(challenge -> challenge.isOwnedBy(user.getId()) && challenge.isInProgress()))
                .hasSize(1);
    }

    @Test
    @DisplayName("동일 챌린지 조정 순번 UNIQUE는 실제 MySQL 동시 INSERT 중 하나를 거절한다")
    void adjustmentSequenceUniqueRejectsConcurrentInsert() throws Exception {
        User user = newUser("adjustment-unique");
        Challenge challenge = newChallenge(user.getId(), 7, today(), 70000);

        List<Outcome<ChallengeAdjustment>> outcomes = race(
                () -> challengeAdjustmentRepository.saveAndFlush(adjustmentOf(challenge, 77000)),
                () -> challengeAdjustmentRepository.saveAndFlush(adjustmentOf(challenge, 84000)));

        assertConstraintRace(outcomes);
        assertThat(challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(challenge.getId()))
                .singleElement().satisfies(adjustment -> assertThat(adjustment.getSequenceNumber()).isEqualTo(1));
    }

    @Test
    @DisplayName("동일 사용자 미복귀 휴식 UNIQUE는 실제 MySQL 동시 INSERT 중 하나를 거절한다")
    void unresumedRestUniqueRejectsConcurrentInsert() throws Exception {
        User user = newUser("rest-unique");
        LocalDate today = today();

        List<Outcome<UserRest>> outcomes = race(
                () -> userRestRepository.saveAndFlush(UserRest.start(user.getId(), today, 7)),
                () -> userRestRepository.saveAndFlush(UserRest.start(user.getId(), today, 14)));

        assertConstraintRace(outcomes);
        assertThat(userRestRepository.findAll().stream()
                .filter(rest -> rest.getUserId().equals(user.getId()) && rest.isActiveOn(today)))
                .hasSize(1);
    }

    @Test
    @DisplayName("같은 유저의 챌린지 생성 두 건은 하나만 성공하고 다른 한 건은 409이며 진행 중 행은 하나다")
    void serializesConcurrentChallengeCreation() throws Exception {
        User user = newUser("create");
        LocalDate today = today();

        OrderedRace<CreateChallengeResponse, CreateChallengeResponse> outcomes = orderedRace(
                () -> challengeService.create(user.getId(), createRequest(today, 70000)),
                () -> challengeService.create(user.getId(), createRequest(today, 84000)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertConflict(outcomes.second(), ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        Challenge active = challengeRepository.findInProgress(user.getId()).orElseThrow();
        assertThat(active.getBudgetTotal()).isEqualTo(70000);
    }

    @Test
    @DisplayName("서로 다른 사용자의 챌린지 생성은 한쪽 사용자 락을 해제하기 전에 독립적으로 완료된다")
    void doesNotBlockChallengeCreationForDifferentUsers() throws Exception {
        User firstUser = newUser("different-user-first");
        User secondUser = newUser("different-user-second");
        LocalDate today = today();

        pausingUserOperationLock.pauseNextLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<CreateChallengeResponse>> firstFuture = executor.submit(() -> capture(
                    () -> challengeService.create(firstUser.getId(), createRequest(today, 70000))));
            assertThat(pausingUserOperationLock.awaitLock()).isTrue();

            Future<Outcome<CreateChallengeResponse>> secondFuture = executor.submit(() -> capture(
                    () -> challengeService.create(secondUser.getId(), createRequest(today, 84000))));

            assertThat(pausingUserOperationLock.awaitAdditionalLock()).isTrue();
            Outcome<CreateChallengeResponse> secondOutcome = secondFuture.get(15, TimeUnit.SECONDS);
            assertThat(secondOutcome.succeeded()).isTrue();
            assertThat(firstFuture.isDone()).isFalse();

            pausingUserOperationLock.release();
            assertThat(firstFuture.get(15, TimeUnit.SECONDS).succeeded()).isTrue();
        } finally {
            pausingUserOperationLock.release();
            executor.shutdownNow();
        }

        assertThat(challengeRepository.findInProgress(firstUser.getId()))
                .get().extracting(Challenge::getBudgetTotal).isEqualTo(70000);
        assertThat(challengeRepository.findInProgress(secondUser.getId()))
                .get().extracting(Challenge::getBudgetTotal).isEqualTo(84000);
    }

    @Test
    @DisplayName("동일한 날짜 고정 챌린지 시작 요청을 동시에 보내면 챌린지는 한 번만 생성되고 두 요청은 같은 결과를 받는다")
    void serializesConcurrentFixedDateStartIdempotently() throws Exception {
        User user = newUser("fixed-date-start");
        LocalDate today = today();
        Challenge source = endedFixedDateSource(user.getId(), today);
        var request = new StartFixedDateChallengeRequest(
                source.getId(), today, 310000, today.getDayOfMonth());

        OrderedRace<CreateChallengeResponse, CreateChallengeResponse> outcomes = orderedRace(
                () -> challengeService.startFixedDate(user.getId(), request),
                () -> challengeService.startFixedDate(user.getId(), request));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        assertThat(outcomes.second().value().challengeId())
                .isEqualTo(outcomes.first().value().challengeId());
        assertThat(challengeRepository.findAll().stream()
                .filter(challenge -> challenge.isOwnedBy(user.getId())))
                .hasSize(2);
        assertThat(challengeRepository.findInProgress(user.getId()))
                .get().extracting(Challenge::getId)
                .isEqualTo(outcomes.first().value().challengeId());
    }

    @Test
    @DisplayName("날짜 고정 챌린지 시작 중 휴식을 요청하면 휴식은 거절되고 진행 중 챌린지만 남는다")
    void keepsFixedDateChallengeAndRestMutuallyExclusive() throws Exception {
        User user = newUser("fixed-date-rest");
        LocalDate today = today();
        Challenge source = endedFixedDateSource(user.getId(), today);
        var request = new StartFixedDateChallengeRequest(
                source.getId(), today, 310000, today.getDayOfMonth());

        OrderedRace<CreateChallengeResponse, RestStartResponse> outcomes = orderedRace(
                () -> challengeService.startFixedDate(user.getId(), request),
                () -> userRestService.start(user.getId(), new RestStartRequest(7)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertConflict(outcomes.second(), ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        assertThat(challengeRepository.findInProgress(user.getId())).isPresent();
        assertThat(userRestRepository.findActiveOn(user.getId(), today)).isEmpty();
    }

    @Test
    @DisplayName("같은 유저의 휴식 시작 두 건은 하나만 성공하고 다른 한 건은 409이며 활성 휴식은 하나다")
    void serializesConcurrentRestStart() throws Exception {
        User user = newUser("rest");

        OrderedRace<RestStartResponse, RestStartResponse> outcomes = orderedRace(
                () -> userRestService.start(user.getId(), new RestStartRequest(7)),
                () -> userRestService.start(user.getId(), new RestStartRequest(14)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertConflict(outcomes.second(), RestErrorCode.REST_ALREADY_ACTIVE);
        assertThat(userRestRepository.findActiveOn(user.getId(), today())).isPresent();
    }

    @Test
    @DisplayName("챌린지 생성과 휴식 시작 요청이 동시에 들어와도 둘 중 하나만 시작된다")
    void keepsChallengeAndRestMutuallyExclusive() throws Exception {
        User user = newUser("challenge-rest");
        LocalDate today = today();

        OrderedRace<CreateChallengeResponse, RestStartResponse> outcomes = orderedRace(
                () -> challengeService.create(user.getId(), createRequest(today, 70000)),
                () -> userRestService.start(user.getId(), new RestStartRequest(7)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertConflict(outcomes.second(), ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        assertThat(challengeRepository.findInProgress(user.getId())).isPresent();
        assertThat(userRestRepository.findActiveOn(user.getId(), today)).isEmpty();
    }

    @Test
    @DisplayName("활성 휴식 중 챌린지 생성이 먼저 시작되고 휴식 연장이 겹치면 연장은 생성 커밋을 기다린 뒤 REST_NOT_ACTIVE로 끝나고 활성 휴식이 남지 않는다")
    void keepsRestClosedWhenChallengeCreationPrecedesExtension() throws Exception {
        User user = newUser("challenge-rest-extension");
        LocalDate today = today();
        UserRest rest = userRestRepository.saveAndFlush(UserRest.start(user.getId(), today, 7));

        OrderedRace<CreateChallengeResponse, RestResumeResponse> outcomes = orderedRace(
                () -> challengeService.create(user.getId(), createRequest(today, 70000)),
                () -> userRestService.resume(
                        user.getId(), new RestResumeRequest(ResumeWhen.EXTEND, 3)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().error()).isInstanceOfSatisfying(CustomException.class,
                error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RestErrorCode.REST_NOT_ACTIVE);
                    assertThat(error.getErrorCode().getHttpStatus().value()).isEqualTo(404);
                });
        Challenge active = challengeRepository.findInProgress(user.getId()).orElseThrow();
        UserRest reloaded = userRestRepository.findById(rest.getId()).orElseThrow();
        assertThat(active.getBudgetTotal()).isEqualTo(70000);
        assertThat(reloaded.getActualResumeDate()).isEqualTo(today);
        assertThat(reloaded.getPlannedResumeDate()).isEqualTo(today.plusDays(7));
        assertThat(userRestRepository.findActiveOn(user.getId(), today)).isEmpty();
    }

    @Test
    @DisplayName("같은 챌린지의 마지막 목표 조정 두 건은 하나만 성공하고 예산과 이력은 같은 결과를 가리킨다")
    void serializesConcurrentGoalAdjustment() throws Exception {
        User user = newUser("adjust");
        Challenge challenge = newChallenge(user.getId(), 7, today(), 70000);

        OrderedRace<AdjustGoalResponse, AdjustGoalResponse> outcomes = orderedRace(
                () -> challengeService.adjustGoal(user.getId(), challenge.getId(), new AdjustGoalRequest(null, 77000)),
                () -> challengeService.adjustGoal(user.getId(), challenge.getId(), new AdjustGoalRequest(null, 84000)));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertConflict(outcomes.second(), ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        List<ChallengeAdjustment> adjustments = challengeAdjustmentRepository
                .findByChallenge_IdOrderByEffectiveDateAscIdAsc(challenge.getId());
        assertThat(adjustments).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.getSequenceNumber()).isEqualTo(1);
            assertThat(reloaded.getBudgetTotal()).isEqualTo(adjustment.getNewBudgetTotal());
            assertThat(reloaded.getDailyLimit()).isEqualTo(adjustment.getNewDailyLimit());
        });
    }

    @Test
    @DisplayName("목표 조정과 포기가 겹치면 포기 상태가 유지되고 조정 이력과 최종 예산은 한 직렬 순서로 일치한다")
    void serializesGoalAdjustmentAndGiveUp() throws Exception {
        User user = newUser("adjust-give-up");
        Challenge challenge = newChallenge(user.getId(), 7, today(), 70000);

        OrderedRace<AdjustGoalResponse, GiveUpResponse> outcomes = orderedRace(
                () -> challengeService.adjustGoal(user.getId(), challenge.getId(), new AdjustGoalRequest(null, 84000)),
                () -> challengeService.giveUp(user.getId(), challenge.getId()));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        List<ChallengeAdjustment> adjustments = challengeAdjustmentRepository
                .findByChallenge_IdOrderByEffectiveDateAscIdAsc(challenge.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(reloaded.getEndReason()).isEqualTo(EndReason.GIVEN_UP);
        assertThat(adjustments).singleElement().satisfies(adjustment -> {
            assertThat(reloaded.getBudgetTotal()).isEqualTo(adjustment.getNewBudgetTotal());
            assertThat(reloaded.getDailyLimit()).isEqualTo(adjustment.getNewDailyLimit());
        });
    }

    @Test
    @DisplayName("결과 확정이 사용자 락을 먼저 잡으면 최종 종료는 그 커밋 후 확정된 상태를 닫는다")
    void serializesResultFinalizationAndClose() throws Exception {
        User user = newUser("result-close");
        LocalDate today = today();
        Challenge challenge = newChallenge(user.getId(), 7, today.minusDays(7), 70000);

        OrderedRace<ResultResponse, CloseResponse> outcomes = orderedRace(
                () -> challengeService.getResult(user.getId(), challenge.getId()),
                () -> challengeService.close(user.getId(), challenge.getId()));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(reloaded.isExpenseLocked()).isTrue();
    }

    @Test
    @DisplayName("같은 챌린지의 정기 확정이 두 인스턴스에서 겹쳐도 사용자 락 뒤 한 번의 결과로 수렴한다")
    void serializesDuplicateScheduledFinalization() throws Exception {
        User user = newUser("scheduled-finalization");
        LocalDate today = today();
        Challenge challenge = newChallenge(user.getId(), 7, today.minusDays(7), 70000);

        OrderedRace<Void, Void> outcomes = orderedRace(
                () -> {
                    challengeService.finalizeDueChallenge(user.getId(), challenge.getId(), today);
                    return null;
                },
                () -> {
                    challengeService.finalizeDueChallenge(user.getId(), challenge.getId(), today);
                    return null;
                });

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        assertThat(challengeRepository.findById(challenge.getId()).orElseThrow().getStatus())
                .isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("최종 종료가 사용자 락을 먼저 잡으면 추천 조회는 종료 커밋까지 기다린 뒤 닫힌 상태를 유지한다")
    void serializesCloseAndRecommendationWithoutReopeningChallenge() throws Exception {
        User user = newUser("close-recommendation");
        LocalDate today = today();
        Challenge challenge = newChallenge(user.getId(), 7, today.minusDays(7), 70000);

        OrderedRace<CloseResponse, RecommendationResponse> outcomes = orderedRace(
                () -> challengeService.close(user.getId(), challenge.getId()),
                () -> challengeService.getRecommendation(user.getId()));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(reloaded.isExpenseLocked()).isTrue();
    }

    @Test
    @DisplayName("지출 수정과 최종 종료는 사용자 행부터 잠가 교착 없이 한 순서로 완료된다")
    void serializesExpenseUpdateAndCloseWithoutDeadlock() throws Exception {
        User user = newUser("expense-close");
        LocalDate today = today();
        // 챌린지 종료일(today - 1)로 고정 — close() 전엔 규칙 3(당일·전날)의 "전날"에 걸려 통과하고,
        // close()가 잠근 뒤엔 규칙 1(그 챌린지 기간)에 걸려 막히는 걸 순수하게 이 레이스로만 확인한다(#228).
        LocalDate expenseDate = today.minusDays(1);
        Expense expense = expenseRepository.save(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, expenseDate, user));
        Challenge challenge = newChallenge(user.getId(), 7, today.minusDays(7), 70000);
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(challenge);

        OrderedRace<?, CloseResponse> outcomes = orderedRace(
                () -> expenseService.update(user.getId(), expense.getId(), expenseUpdateRequest(expenseDate)),
                () -> challengeService.close(user.getId(), challenge.getId()));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        assertThat(expenseRepository.findById(expense.getId()).orElseThrow().getPrice()).isEqualTo(99000);
        assertThat(challengeRepository.findById(challenge.getId()).orElseThrow().isExpenseLocked()).isTrue();
    }

    @Test
    @DisplayName("미입력 자동 취소 판정이 무지출 기록보다 먼저 사용자 행을 잠그면, 그 기록을 못 본 채 자동 취소로 끝나고 기록 자체는 취소 커밋 뒤에도 그대로 반영된다")
    void autoCancelEvaluationPrecedesNoSpendRecording() throws Exception {
        User user = newUser("auto-cancel-first");
        LocalDate today = today();
        LocalDate missingDate = today.minusDays(1);
        Challenge challenge = newChallenge(user.getId(), 14, today.minusDays(7), 140000);

        OrderedRace<CurrentChallengeResponse, Void> outcomes = orderedRace(
                () -> challengeService.getCurrent(user.getId()),
                () -> {
                    expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(missingDate));
                    return null;
                });

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.first().value().expenseInputState()).isEqualTo(ExpenseInputState.AUTO_CANCELLED);
        assertThat(outcomes.second().succeeded()).isTrue();

        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(reloaded.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(user.getId(), missingDate)).isTrue();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated()).isEqualTo(missingDate);
    }

    @Test
    @DisplayName("무지출 기록이 미입력 자동 취소 판정보다 먼저 사용자 행을 잠그면, 판정은 그 기록을 보고 진행 중 상태를 유지한다")
    void noSpendRecordingPrecedesAutoCancelEvaluation() throws Exception {
        User user = newUser("no-spend-first-vs-cancel");
        LocalDate today = today();
        LocalDate missingDate = today.minusDays(1);
        Challenge challenge = newChallenge(user.getId(), 14, today.minusDays(7), 140000);

        OrderedRace<Void, CurrentChallengeResponse> outcomes = orderedRace(
                () -> {
                    expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(missingDate));
                    return null;
                },
                () -> challengeService.getCurrent(user.getId()));

        assertThat(outcomes.secondWasBlocked()).isTrue();
        assertThat(outcomes.first().succeeded()).isTrue();
        assertThat(outcomes.second().succeeded()).isTrue();
        assertThat(outcomes.second().value().expenseInputState()).isEqualTo(ExpenseInputState.NORMAL);

        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        assertThat(reloaded.getEndReason()).isNull();
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(user.getId(), missingDate)).isTrue();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated()).isEqualTo(missingDate);
    }

    private CreateChallengeRequest createRequest(LocalDate startDate, int budgetTotal) {
        return new CreateChallengeRequest(7, budgetTotal, startDate, null);
    }

    private ExpenseUpdateRequest expenseUpdateRequest(LocalDate date) {
        return new ExpenseUpdateRequest(
                "저녁", 99000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, date, null);
    }

    private Challenge newChallenge(Long userId, int durationDays, LocalDate startDate, int budgetTotal) {
        return challengeRepository.save(challengeOf(userId, durationDays, startDate, budgetTotal));
    }

    private Challenge challengeOf(Long userId, int durationDays, LocalDate startDate, int budgetTotal) {
        return Challenge.builder()
                .userId(userId)
                .durationDays(durationDays)
                .startDate(startDate)
                .budgetTotal(budgetTotal)
                .dailyLimit(budgetTotal / durationDays)
                .build();
    }

    private Challenge endedFixedDateSource(Long userId, LocalDate today) {
        Challenge source = Challenge.builder()
                .userId(userId)
                .durationDays(7)
                .startDate(today.minusDays(7))
                .budgetTotal(280000)
                .dailyLimit(40000)
                .fixedDay(today.getDayOfMonth())
                .build();
        source.applyResult(ChallengeStatus.SUCCESS);
        return challengeRepository.saveAndFlush(source);
    }

    private ChallengeAdjustment adjustmentOf(Challenge challenge, int newBudgetTotal) {
        return ChallengeAdjustment.builder()
                .challenge(challenge)
                .sequenceNumber(1)
                .effectiveDate(today())
                .previousBudgetTotal(70000)
                .newBudgetTotal(newBudgetTotal)
                .previousDailyLimit(10000)
                .newDailyLimit(newBudgetTotal / 7)
                .build();
    }

    private User newUser(String scenario) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.createLocalUser(
                scenario + "-" + suffix + "@hampouch.test", "encoded", "동시성" + suffix));
    }

    private LocalDate today() {
        return LocalDate.now(SEOUL);
    }

    private <T> List<Outcome<T>> race(Callable<? extends T> first, Callable<? extends T> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<T>> firstFuture = executor.submit(() -> runAfterBarrier(barrier, first));
            Future<Outcome<T>> secondFuture = executor.submit(() -> runAfterBarrier(barrier, second));
            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> Outcome<T> runAfterBarrier(CyclicBarrier barrier, Callable<? extends T> request) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return new Outcome<>(request.call(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private <T> Outcome<T> capture(Callable<? extends T> request) {
        try {
            return new Outcome<>(request.call(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private <F, S> OrderedRace<F, S> orderedRace(
            Callable<? extends F> first, Callable<? extends S> second) throws Exception {
        pausingUserOperationLock.pauseNextLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<Outcome<F>> firstFuture = executor.submit(() -> capture(first));
            assertThat(pausingUserOperationLock.awaitLock()).isTrue();
            Future<Outcome<S>> secondFuture = executor.submit(() -> {
                secondStarted.countDown();
                return capture(second);
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean secondWasBlocked;
            try {
                secondFuture.get(500, TimeUnit.MILLISECONDS);
                secondWasBlocked = false;
            } catch (TimeoutException expected) {
                secondWasBlocked = true;
            } finally {
                pausingUserOperationLock.release();
            }
            return new OrderedRace<>(
                    firstFuture.get(15, TimeUnit.SECONDS),
                    secondFuture.get(15, TimeUnit.SECONDS),
                    secondWasBlocked);
        } finally {
            pausingUserOperationLock.release();
            executor.shutdownNow();
        }
    }

    private Outcome<?> singleFailure(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow();
    }

    private void assertConflict(Outcome<?> outcome, BaseErrorCode expected) {
        assertThat(outcome.error()).isInstanceOfSatisfying(CustomException.class,
                error -> {
                    assertThat(error.getErrorCode()).isEqualTo(expected);
                    assertThat(error.getErrorCode().getHttpStatus().value()).isEqualTo(409);
                });
    }

    private void assertConstraintRace(List<? extends Outcome<?>> outcomes) {
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(singleFailure(outcomes).error()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PausingUserLockConfig {

        @Bean
        @Primary
        PausingUserOperationLock pausingUserOperationLock(UserRepository userRepository) {
            return new PausingUserOperationLock(userRepository);
        }
    }

    static class PausingUserOperationLock extends UserOperationLock {

        private final AtomicBoolean pauseNext = new AtomicBoolean();
        private volatile CountDownLatch locked = new CountDownLatch(0);
        private volatile CountDownLatch additionalLock = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        PausingUserOperationLock(UserRepository userRepository) {
            super(userRepository);
        }

        public synchronized void pauseNextLock() {
            locked = new CountDownLatch(1);
            additionalLock = new CountDownLatch(1);
            release = new CountDownLatch(1);
            pauseNext.set(true);
        }

        public boolean awaitLock() throws InterruptedException {
            return locked.await(10, TimeUnit.SECONDS);
        }

        public boolean awaitAdditionalLock() throws InterruptedException {
            return additionalLock.await(10, TimeUnit.SECONDS);
        }

        public void release() {
            release.countDown();
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long userId) {
            super.lock(userId);
            if (!pauseNext.compareAndSet(true, false)) {
                additionalLock.countDown();
                return;
            }
            locked.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("사용자 잠금 일시정지가 제한 시간 안에 해제되지 않았습니다.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("사용자 잠금 일시정지가 중단됐습니다.", e);
            }
        }
    }

    private record OrderedRace<F, S>(Outcome<F> first, Outcome<S> second, boolean secondWasBlocked) {
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
