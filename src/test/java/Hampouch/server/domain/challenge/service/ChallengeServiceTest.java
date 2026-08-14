package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.exception.ChallengeNotClosableException;
import Hampouch.server.domain.challenge.repository.ChallengeAdjustmentRepository;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.EmotionSpending;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.expense.service.ExpenseSpendingQuery;
import Hampouch.server.domain.expense.service.PeriodSpending;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER = 1L;
    private static final List<ChallengeStatus> RECOMMENDATION_STATUSES = List.of(
            ChallengeStatus.SUCCESS, ChallengeStatus.FAIL, ChallengeStatus.VOID);

    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    ChallengeDayRepository challengeDayRepository;
    @Mock
    ExpenseService expenseService;
    @Mock
    ExpenseSpendingQuery expenseSpendingQuery; // #64
    @Mock
    UserRestRepository userRestRepository; // 휴식(#8) 연동분 — 목이 기본으로 빈 Optional을 돌려줘 기존 시나리오(휴식 없음)는 스텁 없이 그대로 통과
    @Mock
    ChallengeAdjustmentRepository challengeAdjustmentRepository; // 조정(#7) — 목 기본값이 count 0·빈 리스트라 조정 없는 시나리오는 스텁 없이 통과
    @Mock
    UserOperationLock userOperationLock;

    @BeforeEach
    void defaultExpenseInput() {
        lenient().when(expenseService.hasDayRecord(anyLong(), any(LocalDate.class)))
                .thenReturn(true);
        // getResult()가 항상 호출하므로 기본값을 빈 집계로 깔아 둔다(#64) — 실제 배선을 보는 테스트만 스텁을 따로 덮어쓴다.
        lenient().when(expenseSpendingQuery.periodSpending(anyLong(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new PeriodSpending(0, List.of()));
    }

    private ChallengeService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new ChallengeService(challengeRepository, challengeDayRepository,
                expenseService, expenseSpendingQuery, challengeAdjustmentRepository, userRestRepository,
                userOperationLock, clock);
    }

    @Test
    @DisplayName("생성하면 하루 한도(예산 100,000원 ÷ 기간 30일 = 3,333원 버림)와 종료일(시작일 포함 30일째 되는 날)이 계산돼 저장된다 (S6)")
    void create_computesLimitAndEndDate() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateChallengeRequest(30, 100000, LocalDate.of(2026, 6, 23),
                false, null, List.of("카페음료"));
        CreateChallengeResponse res = serviceAt(LocalDate.of(2026, 6, 23)).create(USER, req);

        assertThat(res.dailyLimit()).isEqualTo(3333);
        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(res.status()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("포기한 날 새 챌린지를 시작하면 당일 기록의 귀속과 금액은 이어받고 새 하루 한도로 다시 판정한다")
    void create_transfersSameDayRecordAndRejudgesIt() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge previous = Challenge.builder()
                .userId(USER).durationDays(7).startDate(today.minusDays(2))
                .budgetTotal(70000).dailyLimit(10000).build();
        ReflectionTestUtils.setField(previous, "id", 10L);
        previous.giveUp(today);
        ChallengeDay day = ChallengeDay.of(previous, today, 12000, DayStatus.OVER, 10000);
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> {
            Challenge saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, List.of(ChallengeStatus.FAIL))).thenReturn(Optional.of(previous));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.of(day));

        serviceAt(today).create(USER,
                new CreateChallengeRequest(7, 105000, today, false, null, null));

        assertThat(day.getChallenge().getId()).isEqualTo(20L);
        assertThat(day.getSpentAmount()).isEqualTo(12000);
        assertThat(day.getDailyLimit()).isEqualTo(15000);
        assertThat(day.getStatus()).isEqualTo(DayStatus.SUCCESS);
    }

    @Test
    @DisplayName("포기한 날 기록이 없으면 새 챌린지 첫날 기록을 만들지 않는다")
    void create_doesNotCreateSameDayRecordWhenPreviousHasNone() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge previous = inProgressWithId(10L, today.minusDays(2));
        previous.giveUp(today);
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, List.of(ChallengeStatus.FAIL))).thenReturn(Optional.of(previous));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.empty());

        serviceAt(today).create(USER,
                new CreateChallengeRequest(7, 105000, today, false, null, null));

        verify(challengeDayRepository, never()).save(any(ChallengeDay.class));
    }

    @Test
    @DisplayName("이미 진행 중인 챌린지가 있으면 생성이 409로 거절된다 (S6)")
    void create_conflictWhenInProgressExists() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(true);
        var req = new CreateChallengeRequest(14, 280000, LocalDate.of(2026, 6, 1), false, null, null);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 1)).create(USER, req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("진행 중 존재 검사를 통과했더라도 저장 시점에 데이터베이스 유니크 제약 위반이 나면 이미 진행 중과 같은 409 에러 코드로 변환된다 — 동시 생성 경쟁의 마지막 방어선")
    void create_conflictWhenConcurrentInsertHitsUniqueConstraint() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(challengeRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_challenge_active_user"));
        var req = new CreateChallengeRequest(14, 280000, LocalDate.of(2026, 6, 1), false, null, null);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 1)).create(USER, req))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS));
    }

    @Test
    @DisplayName("날짜 고정 챌린지를 처음 만들면 오늘부터 첫 고정일 전날까지의 기간과 하루 한도를 계산한다")
    void create_fixedDateComputesFirstCycle() {
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));
        var req = new CreateChallengeRequest(null, 250000, LocalDate.of(2026, 8, 7),
                true, 1, List.of("카페"));

        CreateChallengeResponse res = serviceAt(LocalDate.of(2026, 8, 7)).create(USER, req);

        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(res.dailyLimit()).isEqualTo(10000);
        ArgumentCaptor<Challenge> saved = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(saved.capture());
        assertThat(saved.getValue().getDurationDays()).isEqualTo(25);
        assertThat(saved.getValue().isFixedDate()).isTrue();
        assertThat(saved.getValue().getPaydayDay()).isEqualTo(1);
    }

    @Test
    @DisplayName("날짜 고정 챌린지를 처음 만들 때 시작일을 미래로 지정하면 요청을 거절한다")
    void create_fixedDateRejectsFutureInitialStart() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        var req = new CreateChallengeRequest(
                null, 250000, today.plusDays(1), true, 1, null);

        assertThatThrownBy(() -> serviceAt(today).create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.BAD_REQUEST);
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재 날짜 고정 챌린지가 끝나기 전에는 다음 챌린지 정보를 조회할 수 없다")
    void nextFixedDateChallenge_rejectsBeforeCurrentCycleEnds() {
        Challenge source = fixedChallengeWithId(
                10L, LocalDate.of(2026, 8, 7), 25, 250000, 10000, 1, ChallengeStatus.IN_PROGRESS);
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(source));
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(source));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 8, 20))
                .getNextFixedDateChallenge(USER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.FIXED_DATE_NOT_DUE);
    }

    @Test
    @DisplayName("예정된 시작일이 지난 뒤 조회하면 오늘부터 다음 고정일 전날까지 계산한다")
    void nextFixedDateChallenge_startsTodayWhenOpenedLate() {
        Challenge source = fixedChallengeWithId(
                10L, LocalDate.of(2026, 8, 7), 25, 270000, 10800, 1, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(source));

        NextFixedDateChallengeResponse res = serviceAt(LocalDate.of(2026, 9, 4))
                .getNextFixedDateChallenge(USER);

        assertThat(res.sourceChallengeId()).isEqualTo(10L);
        assertThat(res.fixedDay()).isEqualTo(1);
        assertThat(res.startDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(res.durationDays()).isEqualTo(27);
        assertThat(res.budgetTotal()).isEqualTo(270000);
        assertThat(res.dailyLimit()).isEqualTo(10000);
    }

    @Test
    @DisplayName("기간 챌린지로 전환하면 다음 날짜 고정 챌린지 정보를 조회할 수 없다")
    void nextFixedDateChallenge_notFoundAfterSwitchingToPeriod() {
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));
        var period = new CreateChallengeRequest(
                14, 280000, LocalDate.of(2026, 9, 1), false, null, null);
        serviceAt(LocalDate.of(2026, 9, 1)).create(USER, period);

        ArgumentCaptor<Challenge> saved = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(saved.capture());
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(saved.getValue()));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 9, 2)).getNextFixedDateChallenge(USER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.FIXED_DATE_SETTING_NOT_FOUND);
    }

    @Test
    @DisplayName("날짜 고정 챌린지를 시작하면 요청한 목표와 고정일로 새 챌린지를 만들고 활성 휴식을 종료한다")
    void startFixedDate_createsRequestedCycleAndClosesRest() {
        LocalDate today = LocalDate.of(2026, 9, 3);
        Challenge source = fixedChallengeWithId(
                10L, LocalDate.of(2026, 8, 7), 25, 250000, 10000, 1, ChallengeStatus.SUCCESS);
        UserRest rest = UserRest.start(USER, LocalDate.of(2026, 9, 1), 7);
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(source));
        when(userRestRepository.findActiveOn(USER, today)).thenReturn(Optional.of(rest));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> {
            Challenge challenge = inv.getArgument(0);
            ReflectionTestUtils.setField(challenge, "id", 11L);
            return challenge;
        });
        var req = new StartFixedDateChallengeRequest(10L, today, 350000, 10);

        CreateChallengeResponse res = serviceAt(today).startFixedDate(USER, req);

        assertThat(res.challengeId()).isEqualTo(11L);
        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(res.dailyLimit()).isEqualTo(50000);
        assertThat(rest.getActualResumeDate()).isEqualTo(today);
        ArgumentCaptor<Challenge> saved = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(saved.capture());
        assertThat(saved.getValue().getDurationDays()).isEqualTo(7);
        assertThat(saved.getValue().getPaydayDay()).isEqualTo(10);
    }

    @Test
    @DisplayName("동일한 날짜 고정 챌린지 시작 요청을 다시 보내면 새 챌린지를 만들지 않고 기존 결과를 반환한다")
    void startFixedDate_isIdempotentForSameSourceAndRequest() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        Challenge source = fixedChallengeWithId(
                10L, LocalDate.of(2026, 8, 7), 25, 300000, 12000, 1, ChallengeStatus.SUCCESS);
        Challenge active = fixedChallengeWithId(
                11L, today, 30, 300000, 10000, 1, ChallengeStatus.IN_PROGRESS);
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(active));
        when(challengeRepository.findFirstByUserIdAndIdNotOrderByCreatedAtDescIdDesc(USER, 11L))
                .thenReturn(Optional.of(source));
        var req = new StartFixedDateChallengeRequest(10L, today, 300000, 1);

        CreateChallengeResponse res = serviceAt(today).startFixedDate(USER, req);

        assertThat(res.challengeId()).isEqualTo(11L);
        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("최신 날짜 고정 챌린지의 ID가 아닌 값으로 시작하면 요청을 거절한다")
    void startFixedDate_rejectsStaleSource() {
        Challenge latest = fixedChallengeWithId(
                12L, LocalDate.of(2026, 8, 7), 25, 300000, 12000, 1, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(latest));
        var req = new StartFixedDateChallengeRequest(
                10L, LocalDate.of(2026, 9, 1), 300000, 1);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 9, 1)).startFixedDate(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.FIXED_DATE_SOURCE_STALE);
    }

    @Test
    @DisplayName("날짜 고정 챌린지를 중도 종료해도 원래 종료일 전에는 다음 챌린지를 시작할 수 없다")
    void startFixedDate_rejectsBeforeDueDate() {
        Challenge source = fixedChallengeWithId(
                10L, LocalDate.of(2026, 8, 7), 25, 300000, 12000, 1, ChallengeStatus.FAIL);
        when(challengeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                .thenReturn(Optional.of(source));
        var req = new StartFixedDateChallengeRequest(
                10L, LocalDate.of(2026, 8, 20), 300000, 1);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 8, 20)).startFixedDate(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.FIXED_DATE_NOT_DUE);
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("종료일이 지나기 전에 결과를 요청하면 409를 던진다 (S5)")
    void result_conflictWhileInProgress() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 10)).getResult(USER, 10L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("종료일이 지났고 전일 성공이면 결과 조회 때 SUCCESS로 확정 저장된다")
    void result_finalizesSuccessAfterEnd() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 10000, DayStatus.SUCCESS, ch.getDailyLimit()),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 2), 12000, DayStatus.SUCCESS, ch.getDailyLimit())));

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(res.summary().successDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("챌린지 기간 밖 날짜로 일별 입력하면 400을 던진다 (S7)")
    void upsertDay_outOfRange() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        var req = new DayUpsertRequest(LocalDate.of(2026, 6, 20), 5000, null, null);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).upsertDay(USER, 10L, req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("같은 날짜로 다시 입력하면 기존 행을 덮어쓰고 판정도 새 금액 기준으로 바뀐다 (S7 upsert)")
    void upsertDay_overwritesExisting() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 1000, DayStatus.SUCCESS, ch.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));

        var req = new DayUpsertRequest(date, 99999, null, null);
        DayUpsertResponse res = serviceAt(LocalDate.of(2026, 6, 5)).upsertDay(USER, 10L, req);

        assertThat(res.spentAmount()).isEqualTo(99999);
        assertThat(res.status()).isEqualTo(DayStatus.OVER);
        assertThat(existing.getSpentAmount()).isEqualTo(99999);
    }

    @Test
    @DisplayName("오늘 챌린지가 없으면 challenge: null만 반환한다.")
    void current_noChallengeReturnsNullChallenge() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.empty());

        CurrentChallengeResponse response = serviceAt(today).getCurrent(USER);

        assertThat(response.challenge()).isNull();
        assertThat(response.progress()).isNull();
    }

    @Test
    @DisplayName("선택 날짜에 챌린지가 없으면 challenge: null만 반환한다.")
    void current_pastDateWithNoChallengeReturnsNullChallenge() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        LocalDate selectedDate = LocalDate.of(2026, 4, 12);
        when(challengeRepository.findActiveOnDate(USER, selectedDate))
                .thenReturn(Optional.empty());

        CurrentChallengeResponse response = serviceAt(today).getCurrent(USER, selectedDate);

        assertThat(response.challenge()).isNull();
        assertThat(response.progress()).isNull();
        verifyNoInteractions(userOperationLock, challengeDayRepository, challengeAdjustmentRepository);
    }

    @Test
    @DisplayName("오늘을 명시한 조회도 사용자 잠금을 거쳐 오늘 챌린지를 조회한다")
    void current_explicitTodayPreservesCurrentBehavior() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.empty());

        CurrentChallengeResponse response = serviceAt(today).getCurrent(USER, today);

        assertThat(response.challenge()).isNull();
        assertThat(response.progress()).isNull();
        verify(userOperationLock).lock(USER);
        verify(challengeRepository).findActiveOnDate(USER, today);
    }

    @Test
    @DisplayName("미래 날짜 홈 조회는 아직 오지 않은 진행도를 만들지 않고 400으로 거절한다")
    void current_futureDateIsRejected() {
        LocalDate today = LocalDate.of(2026, 6, 5);

        assertThatThrownBy(() -> serviceAt(today).getCurrent(USER, today.plusDays(1)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.BAD_REQUEST);

        verifyNoInteractions(challengeRepository, challengeDayRepository, challengeAdjustmentRepository,
                userRestRepository, userOperationLock);
    }

    @Test
    @DisplayName("종료된 챌린지의 조정 전 날짜를 조회하면 그날까지의 진행도·연속 달성 기록과 그날의 지출·하루 한도를 반환한다")
    void current_historicalEndedChallengeUsesSelectedDate() {
        LocalDate selectedDate = LocalDate.of(2026, 6, 3);
        Challenge challenge = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        challenge.adjustGoal(308000, 22000);
        challenge.applyResult(ChallengeStatus.SUCCESS);
        ChallengeAdjustment laterAdjustment = ChallengeAdjustment.builder()
                .challenge(challenge)
                .sequenceNumber(1)
                .effectiveDate(LocalDate.of(2026, 6, 5))
                .option(AdjustOption.PLUS_10)
                .previousBudgetTotal(280000)
                .newBudgetTotal(308000)
                .previousDailyLimit(20000)
                .newDailyLimit(22000)
                .build();
        List<ChallengeDay> days = List.of(
                ChallengeDay.of(challenge, LocalDate.of(2026, 6, 1), 5000, DayStatus.SUCCESS, 20000),
                ChallengeDay.of(challenge, selectedDate, 15000, DayStatus.SUCCESS, 20000));
        when(challengeRepository.findActiveOnDate(USER, selectedDate))
                .thenReturn(Optional.of(challenge));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(days);
        when(challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(10L))
                .thenReturn(List.of(laterAdjustment));

        CurrentChallengeResponse response = serviceAt(LocalDate.of(2026, 6, 20))
                .getCurrent(USER, selectedDate);

        assertThat(response.challenge().status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(response.challenge().dailyLimit()).isEqualTo(20000);
        assertThat(response.progress()).isEqualTo(new CurrentChallengeResponse.Progress(3, 11, 3, 0, 3, 40000));
        assertThat(response.consumption().todaySpent()).isEqualTo(15000);
        assertThat(response.consumption().todayRemaining()).isEqualTo(5000);
        assertThat(response.adjustment().usedCount()).isZero();
        assertThat(response.expenseInputState()).isEqualTo(ExpenseInputState.NORMAL);
        verifyNoInteractions(userOperationLock);
    }

    @Test
    @DisplayName("기간이 종료된 8일 이상 챌린지는 종료일을 포함한 3일 연속 지출 미입력이면 결과 조회 시 VOID로 확정한다")
    void result_autoCancelsWhenNoRecords() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14, dailyLimit 20000
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(ch.getInactiveFrom()).isEqualTo(ch.getEndDate().plusDays(1));
        // 금액 집계의 0원 성공과 입력 제재의 VOID는 서로 다른 결과다.
        assertThat(res.summary().successDays()).isEqualTo(14);
        assertThat(res.summary().savedAmount()).isEqualTo(280000);
        assertThat(res.summary().actualSpent()).isZero();
        verify(expenseService, times(3)).hasDayRecord(eq(USER), any(LocalDate.class));
    }

    @Test
    @DisplayName("기간 중 미입력으로 자동 취소된 VOID 챌린지는 취소 전날까지만 집계한다")
    void result_autoCancelledChallengeAggregatesBeforeInactiveDate() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.cancelForMissingInput(LocalDate.of(2026, 6, 3));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 10)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.VOID);
        assertThat(res.summary().successDays()).isEqualTo(3);
        assertThat(res.summary().savedAmount()).isEqualTo(60000);
        verify(expenseSpendingQuery).periodSpending(
                USER, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3));
    }

    @Test
    @DisplayName("7일 챌린지는 미입력 자동 취소 대상이 아니어서 기록이 없으면 날짜별 0원 집계로 SUCCESS다")
    void result_shortChallengeFinalizesSuccessWhenNoRecords() {
        Challenge ch = Challenge.builder()
                .userId(USER).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 10)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(res.summary().successDays()).isEqualTo(7);
        assertThat(res.summary().savedAmount()).isEqualTo(70000);
        verify(expenseService, never()).hasDayRecord(anyLong(), any(LocalDate.class));
    }

    @Test
    @DisplayName("SUCCESS로 확정된 뒤라도 기간 내 지출을 목표 총액이 넘게 수정하면 결과가 FAIL로 재계산된다")
    void upsertDay_recomputesFinalizedStatus() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ch.applyResult(ChallengeStatus.SUCCESS);
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 1000, DayStatus.SUCCESS, ch.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(existing));

        serviceAt(LocalDate.of(2026, 6, 20)).upsertDay(USER, 10L, new DayUpsertRequest(date, 300000, null, null));

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);
    }

    @Test
    @DisplayName("총지출 초과로 FAIL이 확정된 챌린지는 지출을 낮춰 총지출이 목표 이하가 되면 SUCCESS로 재계산된다 — 포기의 FAIL과 달리 계산된 FAIL은 재계산 대상이다")
    void upsertDay_recalculatesCalculatedFailBackToSuccess() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ch.applyResult(ChallengeStatus.FAIL);
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 300000, DayStatus.OVER, ch.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(existing));

        serviceAt(LocalDate.of(2026, 6, 20)).upsertDay(USER, 10L, new DayUpsertRequest(date, 1000, null, null));

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("applyResult에 결과값이 아닌 상태(IN_PROGRESS)를 넣으면 서버 버그로 보고 IllegalArgumentException이 터진다")
    void applyResult_rejectsNonResultStatus() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        assertThatThrownBy(() -> ch.applyResult(ChallengeStatus.IN_PROGRESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("캘린더 연도가 범위(1~9999) 밖이면 500이 아니라 400으로 거절한다")
    void calendar_badRequestWhenYearOutOfRange() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 10000, 6))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("없는 챌린지에 잘못된 월을 요청하면, 파라미터 검증보다 존재 확인이 먼저라 404가 나온다")
    void calendar_notFoundTakesPrecedenceOverBadMonth() {
        when(challengeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 99L, 2026, 13))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("오늘 사용률이 75%면 캐릭터는 SKINNY·경고는 DANGER — 남은 한도가 (한도−지출) 값으로 제자리에 담기는지도 확인(지출·잔액이 같은 int라 자리가 뒤바뀌어도 컴파일러는 못 잡으므로)")
    void current_consumptionState() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        LocalDate today = LocalDate.of(2026, 6, 5);
        ChallengeDay todayRow = ChallengeDay.of(ch, today, 15000, DayStatus.OVER, ch.getDailyLimit());
        when(challengeRepository.findActiveOnDate(USER, today))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of(todayRow));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any()))
                .thenReturn(Optional.of(todayRow));

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.consumption().usageRate()).isEqualTo(0.75);
        assertThat(res.consumption().character()).isEqualTo(ConsumptionCharacter.SKINNY);
        assertThat(res.consumption().alertLevel()).isEqualTo(AlertLevel.DANGER);
        assertThat(res.consumption().todayRemaining()).isEqualTo(5000);
    }

    @Test
    @DisplayName("홈 현황의 조정 현황은 이력 행 수와 기간별 상한을 그대로 내려준다 — 14일 챌린지의 상한은 1회")
    void current_adjustmentCountsComeFromHistory() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1)); // 14일
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.of(ch));
        when(challengeAdjustmentRepository.countByChallenge_Id(10L)).thenReturn(1);

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.adjustment().usedCount()).isEqualTo(1);
        assertThat(res.adjustment().maxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("+10%를 고르면 목표 금액이 오르고 하루 한도는 그 목표를 기간으로 나눈 값이 된다 — 조정 전후가 이력에 남는다")
    void adjust_appliesOptionToBudgetAndDerivesDailyLimit() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        AdjustGoalResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null));

        assertThat(res.budgetTotal()).isEqualTo(308000);
        assertThat(res.dailyLimit()).isEqualTo(22000);
        assertThat(ch.getBudgetTotal()).isEqualTo(308000);
        assertThat(ch.getDailyLimit()).isEqualTo(22000);
        assertThat(res.usedCount()).isEqualTo(1);

        ArgumentCaptor<ChallengeAdjustment> saved = ArgumentCaptor.forClass(ChallengeAdjustment.class);
        verify(challengeAdjustmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPreviousBudgetTotal()).isEqualTo(280000);
        assertThat(saved.getValue().getNewBudgetTotal()).isEqualTo(308000);
        assertThat(saved.getValue().getPreviousDailyLimit()).isEqualTo(20000);
        assertThat(saved.getValue().getNewDailyLimit()).isEqualTo(22000);
        assertThat(saved.getValue().getSequenceNumber()).isEqualTo(1);
        assertThat(saved.getValue().getEffectiveDate()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    @DisplayName("+30%를 고르면 목표 금액에 1.3배를 적용하고 새 하루 한도와 선택 이력을 저장한다")
    void adjust_appliesPlusThirtyOption() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        AdjustGoalResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_30, null));

        assertThat(res.budgetTotal()).isEqualTo(364000);
        assertThat(res.dailyLimit()).isEqualTo(26000);
        assertThat(ch.getBudgetTotal()).isEqualTo(364000);
        assertThat(ch.getDailyLimit()).isEqualTo(26000);

        ArgumentCaptor<ChallengeAdjustment> saved = ArgumentCaptor.forClass(ChallengeAdjustment.class);
        verify(challengeAdjustmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOption()).isEqualTo(AdjustOption.PLUS_30);
        assertThat(saved.getValue().getNewBudgetTotal()).isEqualTo(364000);
        assertThat(saved.getValue().getNewDailyLimit()).isEqualTo(26000);
    }

    @Test
    @DisplayName("직접 입력한 금액으로도 조정된다 — 배율을 안 거치고 그 값이 목표가 되며 하루 한도는 거기서 파생된다")
    void adjust_acceptsDirectAmount() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        AdjustGoalResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(null, 350000));

        assertThat(res.budgetTotal()).isEqualTo(350000);
        assertThat(res.dailyLimit()).isEqualTo(25000);

        ArgumentCaptor<ChallengeAdjustment> saved = ArgumentCaptor.forClass(ChallengeAdjustment.class);
        verify(challengeAdjustmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOption()).isNull();
        assertThat(saved.getValue().getNewBudgetTotal()).isEqualTo(350000);
    }

    @Test
    @DisplayName("직접 입력 금액이 기간으로 나누어떨어지지 않으면 하루 한도는 버림이다 — 생성 때와 같은 규칙")
    void adjust_floorsDerivedDailyLimit() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        AdjustGoalResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(null, 300005));

        assertThat(res.dailyLimit()).isEqualTo(21428);
    }

    @Test
    @DisplayName("14일 챌린지를 이미 한 번 조정했으면 두 번째 조정은 409로 막히고 목표도 그대로다")
    void adjust_conflictWhenCountExhausted() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeAdjustmentRepository.countByChallenge_Id(10L)).thenReturn(1);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
        assertThat(ch.getBudgetTotal()).isEqualTo(280000);
        assertThat(ch.getDailyLimit()).isEqualTo(20000);
    }

    @Test
    @DisplayName("15일 챌린지는 한 번 조정한 뒤에도 두 번째 조정이 통과한다 — 기간 경계에서 상한이 갈리는지 확인")
    void adjust_allowsSecondOnLongerChallenge() {
        Challenge ch = Challenge.builder()
                .userId(USER).durationDays(15).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(300000).dailyLimit(20000).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeAdjustmentRepository.countByChallenge_Id(10L)).thenReturn(1);

        AdjustGoalResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_20, null));

        assertThat(res.budgetTotal()).isEqualTo(360000);
        assertThat(res.dailyLimit()).isEqualTo(24000);
        assertThat(res.usedCount()).isEqualTo(2);
        assertThat(res.maxCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("기간이 끝났지만 아직 확정 전인 챌린지의 조정은 409로 막힌다 — 끝난 기간의 목표를 올려 결과를 뒤집는 길 차단")
    void adjust_conflictWhenPeriodEnded() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1)); // endDate 2026-06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
    }

    @Test
    @DisplayName("남의 챌린지는 조정할 수 없다")
    void adjust_forbiddenForOtherUser() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(999L, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_FORBIDDEN);
    }

    @Test
    @DisplayName("조정 시점에 오늘 기록이 초과로 남아 있으면 새 한도로 다시 채점된다 — 오늘은 아직 '지난 날'이 아니다")
    void adjust_rejudgesTodayRecord() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        LocalDate today = LocalDate.of(2026, 6, 5);
        ChallengeDay todayRow = ChallengeDay.of(ch, today, 21000, DayStatus.OVER, 20000);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDateGreaterThanEqual(10L, today))
                .thenReturn(List.of(todayRow));

        serviceAt(today).adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null));

        assertThat(todayRow.getStatus()).isEqualTo(DayStatus.SUCCESS);
        assertThat(todayRow.getDailyLimit()).isEqualTo(22000);
    }

    @Test
    @DisplayName("조정 전에 미리 입력해 둔 미래 날짜 기록도 새 한도로 다시 채점된다")
    void adjust_rejudgesFutureRecordsToo() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        LocalDate today = LocalDate.of(2026, 6, 5);
        ChallengeDay futureRow = ChallengeDay.of(ch, LocalDate.of(2026, 6, 10), 21000, DayStatus.OVER, 20000);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDateGreaterThanEqual(10L, today))
                .thenReturn(List.of(futureRow));

        serviceAt(today).adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_20, null));

        assertThat(futureRow.getStatus()).isEqualTo(DayStatus.SUCCESS);
        assertThat(futureRow.getDailyLimit()).isEqualTo(24000);
        verify(challengeDayRepository).findByChallenge_IdAndDayDateGreaterThanEqual(10L, today);
    }

    @Test
    @DisplayName("조정 번호 유니크 제약에 걸린 동시 요청은 409로 바뀐다")
    void adjust_conflictWhenConcurrentRequestTakesTheSlot() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeAdjustmentRepository.saveAndFlush(any(ChallengeAdjustment.class)))
                .thenThrow(new DataIntegrityViolationException("uq_challenge_adjustment_seq"));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .adjustGoal(USER, 10L, new AdjustGoalRequest(AdjustOption.PLUS_10, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("결과 화면의 절약액은 기록된 0원 날짜가 조정 전이면 옛 한도, 조정 후면 새 한도로 집계한다")
    void result_savedAmountSplitsAtAdjustmentDate() {
        // 6/1~6/30, 한도 10000 → 6/5에 PLUS_20으로 12000.
        Challenge ch = Challenge.builder()
                .userId(USER).durationDays(30).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(300000).dailyLimit(12000).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        ch.applyResult(ChallengeStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 4), 0, DayStatus.SUCCESS, 10000),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 5), 0, DayStatus.SUCCESS, 12000)));
        when(challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(10L))
                .thenReturn(List.of(ChallengeAdjustment.builder()
                        .challenge(ch).sequenceNumber(1).effectiveDate(LocalDate.of(2026, 6, 5))
                        .option(AdjustOption.PLUS_20)
                        .previousBudgetTotal(300000).newBudgetTotal(360000)
                        .previousDailyLimit(10000).newDailyLimit(12000).build()));

        ResultResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getResult(USER, 10L);

        assertThat(res.summary().savedAmount()).isEqualTo(4 * 10000 + 26 * 12000);
    }

    @Test
    @DisplayName("조정 뒤에 지난 날짜의 지출을 뒤늦게 입력하면 그날 한도로 채점된다 — 새 한도가 과거로 소급되지 않는다")
    void upsertDay_usesLimitOfThatDayAfterAdjustment() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ReflectionTestUtils.setField(ch, "dailyLimit", 22000);
        LocalDate pastDay = LocalDate.of(2026, 6, 3);
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeAdjustmentRepository.findByChallenge_IdOrderByEffectiveDateAscIdAsc(10L))
                .thenReturn(List.of(ChallengeAdjustment.builder()
                        .challenge(ch).sequenceNumber(1).effectiveDate(LocalDate.of(2026, 6, 5))
                        .option(AdjustOption.PLUS_10)
                        .previousBudgetTotal(280000).newBudgetTotal(308000)
                        .previousDailyLimit(20000).newDailyLimit(22000).build()));
        when(challengeDayRepository.save(any(ChallengeDay.class))).thenAnswer(inv -> inv.getArgument(0));

        DayUpsertResponse res = serviceAt(LocalDate.of(2026, 6, 6))
                .upsertDay(USER, 10L, new DayUpsertRequest(pastDay, 21000, null, null));

        assertThat(res.dailyLimit()).isEqualTo(20000);
        assertThat(res.status()).isEqualTo(DayStatus.OVER);
    }

    @Test
    @DisplayName("집중 카테고리를 중복으로 보내면 중복이 제거되어 저장된다 (유니크 제약 위반 500 방지)")
    void create_deduplicatesWeakCategories() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        ArgumentCaptor<Challenge> captor = ArgumentCaptor.forClass(Challenge.class);
        when(challengeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateChallengeRequest(14, 280000, LocalDate.of(2026, 6, 1),
                false, null, List.of("카페", "카페", "배달"));
        serviceAt(LocalDate.of(2026, 6, 1)).create(USER, req);

        assertThat(captor.getValue().getWeakCategories()).hasSize(2);
    }

    @Test
    @DisplayName("남의 챌린지에 접근하면 403(CHALLENGE_FORBIDDEN)을 던진다")
    void loadOwned_forbiddenWhenNotOwner() {
        Challenge others = Challenge.builder()
                .userId(2L).durationDays(14).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(280000).dailyLimit(20000).build();
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getResult(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_FORBIDDEN);
    }

    @Test
    @DisplayName("진행 현황 집계는 오늘을 포함한 미기록일을 0원 성공으로 반영하고 입력 여부는 별도 상태로 판정한다")
    void current_progressCountsUnrecordedDaysAsZeroSpent() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 5); // 6/1~6/4 미기록, 오늘도 기록 없음
        when(challengeRepository.findActiveOnDate(USER, today))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any())).thenReturn(Optional.empty());

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.progress().successDays()).isEqualTo(5);
        assertThat(res.progress().savedAmountSoFar()).isEqualTo(100000);
        assertThat(res.progress().currentStreak()).isEqualTo(5);
        assertThat(res.warningCards()).isEmpty();
    }

    @Test
    @DisplayName("최근 완료된 이틀에 지출도 '오늘은 안 썼어요'도 없으면 2일 연속 미입력 상태를 돌려주고 챌린지는 계속 진행한다")
    void current_warnsAfterTwoMissingDays() {
        LocalDate today = LocalDate.of(2026, 6, 3);
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.empty());
        when(expenseService.hasDayRecord(USER, LocalDate.of(2026, 6, 2))).thenReturn(false);
        when(expenseService.hasDayRecord(USER, LocalDate.of(2026, 6, 1))).thenReturn(false);

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.expenseInputState()).isEqualTo(ExpenseInputState.TWO_DAYS_MISSING);
        assertThat(res.challenge().status()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        assertThat(ch.getEndReason()).isNull();
    }

    @Test
    @DisplayName("7일을 초과하는 챌린지에서 최근 완료된 3일 모두 지출 입력이 없으면 VOID로 자동 취소하고 자동 취소 상태를 돌려준다")
    void current_autoCancelsAfterThreeMissingDays() {
        LocalDate today = LocalDate.of(2026, 6, 4);
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.empty());
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.expenseInputState()).isEqualTo(ExpenseInputState.AUTO_CANCELLED);
        assertThat(res.challenge().status()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(ch.getInactiveFrom()).isEqualTo(today);
        assertThat(res.warningCards()).isEmpty();
        verify(expenseService, times(3)).hasDayRecord(eq(USER), any(LocalDate.class));
    }

    @Test
    @DisplayName("종료일에 세 번째 미입력일이 완성되면 다음 날 정기 확정이 종료일을 포함한 3일을 기준으로 VOID 처리한다")
    void scheduledFinalization_autoCancelsWhenThirdMissingDayIsEndDate() {
        Challenge ch = Challenge.builder()
                .userId(USER).durationDays(8).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(80000).dailyLimit(10000).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        LocalDate today = ch.getEndDate().plusDays(1);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        serviceAt(today).finalizeDueChallenge(USER, 10L, today);

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getInactiveFrom()).isEqualTo(today);
        verify(expenseService).hasDayRecord(USER, LocalDate.of(2026, 6, 8));
        verify(expenseService).hasDayRecord(USER, LocalDate.of(2026, 6, 7));
        verify(expenseService).hasDayRecord(USER, LocalDate.of(2026, 6, 6));
    }

    @Test
    @DisplayName("최근 미입력일 앞에 지출 또는 '오늘은 안 썼어요' 기록이 있으면 연속 미입력이 끊겨 정상 상태를 돌려준다")
    void current_recordedDayBreaksMissingStreak() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.empty());
        when(expenseService.hasDayRecord(USER, LocalDate.of(2026, 6, 4))).thenReturn(false);
        when(expenseService.hasDayRecord(USER, LocalDate.of(2026, 6, 3))).thenReturn(true);

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.expenseInputState()).isEqualTo(ExpenseInputState.NORMAL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        verify(expenseService, times(2)).hasDayRecord(eq(USER), any(LocalDate.class));
    }

    @Test
    @DisplayName("7일 챌린지는 3일 연속 지출 미입력 자동 취소를 적용하지 않는다")
    void current_sevenDayChallengeIsExemptFromAutoCancel() {
        LocalDate today = LocalDate.of(2026, 6, 4);
        Challenge ch = Challenge.builder()
                .userId(USER).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findActiveOnDate(USER, today)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, today)).thenReturn(Optional.empty());

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.expenseInputState()).isEqualTo(ExpenseInputState.NORMAL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        verifyNoInteractions(expenseService);
    }

    @Test
    @DisplayName("종료일이 지났고 기간 총지출이 목표를 넘으면 결과가 FAIL로 확정 저장된다")
    void result_finalizesFailAfterEnd() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 10000, DayStatus.SUCCESS, ch.getDailyLimit()),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 2), 299999, DayStatus.OVER,
                        ch.getDailyLimit())));

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(res.summary().overDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하는 챌린지라도 월이 13처럼 범위 밖이면 400으로 거절한다")
    void calendar_badRequestWhenMonthOutOfRange() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 2026, 13))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("챌린지 기간과 안 겹치는 달을 요청하면 에러가 아니라 빈 배열을 준다 (달∩기간 교집합 없음)")
    void calendar_emptyWhenMonthOutsidePeriod() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        CalendarResponse res = serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 2026, 8);

        assertThat(res.days()).isEmpty();
    }

    @Test
    @DisplayName("달과 챌린지 기간이 부분만 겹치면 기간 밖 날짜는 응답에 섞이지 않는다 (달∩기간 교집합)")
    void calendar_returnsOnlyDaysWithinPeriod() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDateBetween(
                10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 14)))
                .thenReturn(List.of(ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 5000, DayStatus.SUCCESS, ch.getDailyLimit())));

        CalendarResponse res = serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 2026, 6);

        assertThat(res.days()).hasSize(1);
        assertThat(res.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    @DisplayName("지난 챌린지 목록은 각 챌린지의 총지출과 절약액을 계산한다")
    void history_aggregatesPerChallenge() {
        Challenge c12 = endedWithId(12L, LocalDate.of(2026, 6, 1), 7, 70000, 10000, ChallengeStatus.FAIL);
        // 8번: 5/1~5/7(7일, 한도 10000), 지출 0원으로 SUCCESS 종료된 챌린지
        Challenge c8 = endedWithId(8L, LocalDate.of(2026, 5, 1), 7, 70000, 10000, ChallengeStatus.SUCCESS);
        when(challengeRepository.findCompletedByUserIdOrderByEndDateDescIdDesc(USER))
                .thenReturn(List.of(c12, c8)); // 최근 종료(6/7)가 먼저 — 정렬은 리포지토리 쿼리 몫
        when(challengeDayRepository.findByChallenge_IdIn(List.of(12L, 8L)))
                .thenReturn(List.of(ChallengeDay.of(c12, LocalDate.of(2026, 6, 3), 15000, DayStatus.OVER, c12.getDailyLimit())));

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(res.items()).hasSize(2);
        var first = res.items().getFirst();
        assertThat(first.challengeId()).isEqualTo(12L);
        assertThat(first.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(first.actualSpent()).isEqualTo(15000);
        assertThat(first.savedAmount()).isEqualTo(60000);
        var second = res.items().get(1);
        assertThat(second.challengeId()).isEqualTo(8L);
        assertThat(second.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(second.actualSpent()).isZero();
        assertThat(second.savedAmount()).isEqualTo(70000);
        assertThat(second.budgetTotal()).isEqualTo(70000);
        assertThat(second.durationDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("중도 포기 챌린지의 총지출과 절약액은 포기 전날까지만 집계한다")
    void history_aggregatesGivenUpChallengeBeforeGiveUpDate() {
        Challenge givenUp = Challenge.builder()
                .userId(USER).durationDays(30).startDate(LocalDate.of(2026, 7, 1))
                .budgetTotal(300000).dailyLimit(10000).build();
        givenUp.giveUp(LocalDate.of(2026, 7, 3));
        ReflectionTestUtils.setField(givenUp, "id", 31L);
        when(challengeRepository.findCompletedByUserIdOrderByEndDateDescIdDesc(USER))
                .thenReturn(List.of(givenUp));
        when(challengeDayRepository.findByChallenge_IdIn(List.of(31L))).thenReturn(List.of(
                ChallengeDay.of(givenUp, LocalDate.of(2026, 7, 1), 5000, DayStatus.SUCCESS, 10000),
                ChallengeDay.of(givenUp, LocalDate.of(2026, 7, 3), 9000, DayStatus.SUCCESS, 10000),
                ChallengeDay.of(givenUp, LocalDate.of(2026, 7, 30), 9000, DayStatus.SUCCESS, 10000)));

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 8, 1)).getHistory(USER);

        assertThat(res.items().getFirst().actualSpent()).isEqualTo(5000);
        assertThat(res.items().getFirst().savedAmount()).isEqualTo(15000);
    }

    @Test
    @DisplayName("종료된 챌린지가 없으면 빈 리스트를 주고, 일자 기록 조회 쿼리는 아예 나가지 않는다")
    void history_emptyWhenNothingEnded() {
        when(challengeRepository.findCompletedByUserIdOrderByEndDateDescIdDesc(USER))
                .thenReturn(List.of());

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(res.items()).isEmpty();
        verify(challengeDayRepository, never()).findByChallenge_IdIn(any());
    }

    @Test
    @DisplayName("지난 챌린지 조회는 진행 중 챌린지를 찾거나 확정하지 않는다")
    void history_doesNotInspectOrFinalizeInProgress() {
        when(challengeRepository.findCompletedByUserIdOrderByEndDateDescIdDesc(USER))
                .thenReturn(List.of());

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(res.items()).isEmpty();
        verify(challengeRepository, never()).findInProgress(anyLong());
        verify(challengeRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(userOperationLock, expenseService);
    }

    @Test
    @DisplayName("진행 중 챌린지를 포기하면 즉시 FAIL로 확정되고 종료 사유(GIVEN_UP)가 남는다 — 종료일(endDate)은 원래 목표 기간 그대로 남는다")
    void giveUp_finalizesFailAsDeclared() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        GiveUpResponse res = serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 10L);

        assertThat(res.challengeId()).isEqualTo(10L);
        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);        // 엔티티에 확정 저장(더티 체킹)
        assertThat(ch.getEndReason()).isEqualTo(EndReason.GIVEN_UP);       // 유저 선언 표식 — 재계산 제외 근거
        assertThat(ch.getInactiveFrom()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(ch.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 14)); // 포기해도 원래 목표 기간 유지
    }

    @Test
    @DisplayName("이미 종료된 챌린지를 다시 포기하면 409(CHALLENGE_NOT_IN_PROGRESS)를 던진다")
    void giveUp_conflictWhenAlreadyEnded() {
        Challenge ended = endedWithId(10L, LocalDate.of(2026, 5, 1), 14, 280000, 20000, ChallengeStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
    }

    @Test
    @DisplayName("없는 챌린지를 포기하면 404(CHALLENGE_NOT_FOUND)를 던진다")
    void giveUp_notFound() {
        when(challengeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 99L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 챌린지를 포기하려 하면, 이미 종료된 것이라도 상태(409)보다 소유 검사가 먼저라 403이 나온다")
    void giveUp_forbiddenWhenNotOwner() {
        Challenge others = Challenge.builder()
                .userId(2L).durationDays(14).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(280000).dailyLimit(20000).build();
        others.applyResult(ChallengeStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_FORBIDDEN);
    }

    @Test
    @DisplayName("정기 확정 전에 기간이 종료된 챌린지의 포기 요청이 들어오면 409로 거절하고 상태를 바꾸지 않는다")
    void giveUp_conflictsWhenPeriodEndedWithoutMutatingState() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 정기 확정 전이라 기간 종료 후에도 IN_PROGRESS
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20)).giveUp(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS); // 거절 경로는 상태를 바꾸지 않는다.
        assertThat(ch.getEndReason()).isNull();                            // 포기 표식도 남지 않음(거절됐으므로)
        verify(challengeDayRepository, never()).findByChallenge_Id(any());  // 기간 종료 결과 확정용 일별 기록 조회 자체가 나가지 않는다
    }

    @Test
    @DisplayName("챌린지 마지막 날(endDate 당일)의 포기는 아직 기간 중이라 정상 처리된다 — 기간 종료에 따른 자동 확정은 endDate가 지난 뒤에야 일어난다")
    void giveUp_allowedOnEndDate() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        GiveUpResponse res = serviceAt(LocalDate.of(2026, 6, 14)).giveUp(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.GIVEN_UP);
    }

    @Test
    @DisplayName("기간이 끝난 챌린지를 최종 종료하면 결과 화면을 한 번도 안 열었어도 그 자리에서 성패가 확정되고 종료 시각이 남는다")
    void close_finalizesPeriodEndedChallengeAndRecordsTime() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, 목표 280000
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 10000, DayStatus.SUCCESS, ch.getDailyLimit())));

        CloseResponse res = serviceAt(LocalDate.of(2026, 6, 20)).close(USER, 10L);

        assertThat(res.challengeId()).isEqualTo(10L);
        assertThat(res.status()).isEqualTo(ChallengeStatus.SUCCESS); // 총지출 10000 ≤ 목표 280000
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(ch.getExpenseLockedAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 12, 0));
    }

    @Test
    @DisplayName("기간이 종료된 8일 이상 챌린지의 마지막 3일이 미입력이면 최종 종료보다 VOID 확정이 우선한다")
    void close_autoCancelsBeforeFinalizingResult() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20)).close(USER, 10L))
                .isInstanceOf(ChallengeNotClosableException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_CLOSABLE);

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(ch.isExpenseLocked()).isFalse();
        verify(challengeDayRepository, never()).findByChallenge_Id(10L);
    }

    @Test
    @DisplayName("이미 결과가 확정된 챌린지를 최종 종료하면 성패는 그대로 두고 종료 시각만 남긴다")
    void close_locksAlreadyFinalizedChallenge() {
        Challenge ended = endedWithId(10L, LocalDate.of(2026, 5, 1), 14, 280000, 20000, ChallengeStatus.FAIL);
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ended));

        CloseResponse res = serviceAt(LocalDate.of(2026, 6, 5)).close(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ended.getExpenseLockedAt()).isEqualTo(LocalDateTime.of(2026, 6, 5, 12, 0));
        verify(challengeDayRepository, never()).findByChallenge_Id(any()); // 확정이 끝나 있어 재계산 조회가 안 나간다
    }

    @Test
    @DisplayName("기간이 아직 안 끝난 챌린지를 최종 종료하려 하면 409(CHALLENGE_NOT_ENDED)로 거절하고 지출 변경 금지 상태로 만들지 않는다 — 마지막 날 당일도 아직 기간 중이다")
    void close_conflictWhenNotEnded() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 14)).close(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_ENDED);

        assertThat(ch.isExpenseLocked()).isFalse();
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("이미 최종 종료한 챌린지를 다시 종료하려 하면 409(CHALLENGE_ALREADY_CLOSED)로 거절하고 첫 종료 시각을 유지한다")
    void close_conflictWhenAlreadyClosed() {
        Challenge ended = endedWithId(10L, LocalDate.of(2026, 5, 1), 14, 280000, 20000, ChallengeStatus.SUCCESS);
        ended.lockExpenseChanges(LocalDateTime.of(2026, 5, 20, 9, 0));
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).close(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED);

        assertThat(ended.getExpenseLockedAt()).isEqualTo(LocalDateTime.of(2026, 5, 20, 9, 0));
    }

    @Test
    @DisplayName("중도 포기한 챌린지는 최종 종료 대상이 아니라 409(CHALLENGE_NOT_CLOSABLE)로 거절한다 — 포기 결과는 지출을 고쳐도 재계산되지 않으므로 해당 기간을 지출 변경 금지 상태로 만들지 않는다")
    void close_conflictWhenGivenUp() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ReflectionTestUtils.setField(ch, "id", 10L);
        ch.giveUp(LocalDate.of(2026, 6, 5));
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20)).close(USER, 10L))
                .isInstanceOf(ChallengeNotClosableException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_CLOSABLE);

        assertThat(ch.isExpenseLocked()).isFalse();
    }

    @Test
    @DisplayName("없는 챌린지를 최종 종료하면 404(CHALLENGE_NOT_FOUND)를 던진다")
    void close_notFound() {
        when(challengeRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).close(USER, 99L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    @DisplayName("최종 종료한 챌린지에 일별 지출을 다시 보내면 409(CHALLENGE_ALREADY_CLOSED)로 거절하고 기존 기록을 그대로 둔다")
    void upsertDay_conflictWhenClosed() {
        Challenge ended = endedWithId(10L, LocalDate.of(2026, 6, 1), 14, 280000, 20000, ChallengeStatus.SUCCESS);
        ended.lockExpenseChanges(LocalDateTime.of(2026, 6, 20, 9, 0));
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ended, date, 1000, DayStatus.SUCCESS, ended.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 21))
                .upsertDay(USER, 10L, new DayUpsertRequest(date, 99999, null, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED);

        assertThat(existing.getSpentAmount()).isEqualTo(1000);
    }

    @Test
    @DisplayName("최종 종료를 누르지 않은 채 기간만 끝난 챌린지가 있어도 새 챌린지를 만들 수 있다 — 정기 작업이 기간 종료 결과를 확정하며, 정기 확정 전에 생성 요청이 들어오면 생성 요청에서도 같은 확정을 수행한 뒤 새 챌린지를 만든다.")
    void create_allowedWhenPreviousPeriodEndedButNeverClosed() {
        Challenge periodEnded = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, 종료를 안 누른 상태
        ReflectionTestUtils.setField(periodEnded, "id", 10L);
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(periodEnded));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        when(challengeRepository.existsInProgress(USER)).thenReturn(false); // 위에서 확정돼 진행 중이 아님
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateChallengeRequest(7, 70000, LocalDate.of(2026, 6, 20), false, null, null);
        CreateChallengeResponse res = serviceAt(LocalDate.of(2026, 6, 20)).create(USER, req);

        assertThat(res.status()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        assertThat(periodEnded.getStatus()).isEqualTo(ChallengeStatus.SUCCESS); // 생성 경로가 확정
        assertThat(periodEnded.isExpenseLocked()).isFalse();                    // 확정과 잠금은 다른 축
    }

    @Test
    @DisplayName("포기한 챌린지의 기간 내 지출을 전부 성공으로 고쳐도 챌린지 전체 결과(FAIL)가 SUCCESS로 되살아나지 않는다 — 그날그날의 금액·판정 수정은 반영되며, '종료 뒤 지출을 고치면 전체 결과도 다시 계산한다'는 규칙에서 포기 챌린지만 빠진다")
    void upsertDay_doesNotResurrectGivenUpFail() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, dailyLimit 20000
        ch.giveUp(LocalDate.of(2026, 6, 3)); // FAIL + GIVEN_UP — 유일한 기록이 초과(아래)여도 포기가 선행된 상황
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 99999, DayStatus.OVER, ch.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));

        serviceAt(LocalDate.of(2026, 6, 10)).upsertDay(USER, 10L, new DayUpsertRequest(date, 1000, null, null));

        assertThat(existing.getSpentAmount()).isEqualTo(1000);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);
        verify(challengeDayRepository, never()).findByChallenge_Id(any());
    }

    @Test
    @DisplayName("미입력으로 자동 취소된 챌린지에 지출을 입력해도 VOID 상태가 다시 계산돼 SUCCESS나 FAIL로 바뀌지 않는다")
    void upsertDay_doesNotResurrectAutoCancelledChallenge() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.cancelForMissingInput(LocalDate.of(2026, 6, 3));
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 0, DayStatus.SUCCESS, ch.getDailyLimit());
        when(challengeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));

        serviceAt(LocalDate.of(2026, 6, 10))
                .upsertDay(USER, 10L, new DayUpsertRequest(date, 1000, null, null));

        assertThat(existing.getSpentAmount()).isEqualTo(1000);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        verify(challengeDayRepository, never()).findByChallenge_Id(10L);
    }

    @Test
    @DisplayName("Challenge 객체의 giveUp 메서드를 서비스 검사를 거치지 않고 이미 끝난 챌린지에 직접 호출하면, 서버 버그로 보고 IllegalStateException이 터진다 — 끝난 챌린지에 또 누르는 클라이언트 실수는 서비스가 409로 먼저 걸러내므로, 이 예외까지 왔다면 검사를 건너뛰고 호출한 서버 코드 실수라는 뜻")
    void giveUp_entityRejectsNonInProgress() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ch.applyResult(ChallengeStatus.SUCCESS);

        assertThatThrownBy(() -> ch.giveUp(LocalDate.of(2026, 6, 5)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("휴식 중에 새 챌린지를 생성하면 그 휴식이 오늘 날짜로 자동 종료된 뒤 생성이 진행된다 — 다시 챌린지 시작하기 흐름")
    void create_closesOpenRest() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        UserRest rest = UserRest.start(USER, LocalDate.of(2026, 7, 6), 7);
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, today)).thenReturn(Optional.of(rest));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateChallengeRequest(7, 70000, today, false, null, null);
        CreateChallengeResponse res = serviceAt(today).create(USER, req);

        assertThat(res.status()).isEqualTo(ChallengeStatus.IN_PROGRESS);
        assertThat(rest.getActualResumeDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("내일부터 복귀가 예약된 휴식 중 오늘 새 챌린지를 생성하면 휴식 종료일을 오늘로 당긴다")
    void create_pullsForwardTomorrowResumeRest() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        UserRest rest = UserRest.start(USER, LocalDate.of(2026, 7, 6), 7);
        rest.resume(today.plusDays(1));
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, today)).thenReturn(Optional.of(rest));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceAt(today).create(USER, new CreateChallengeRequest(7, 70000, today, false, null, null));

        assertThat(rest.getActualResumeDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("기간이 아직 안 끝난 진행 중 챌린지가 있으면 진행 중 챌린지 존재 판단은 참이고, 그 챌린지를 종료로 확정하는 일도 일어나지 않는다")
    void hasActiveChallenge_trueWhileOngoing() {
        Challenge ongoing = inProgress(LocalDate.of(2026, 6, 1));
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(ongoing));
        when(challengeRepository.existsInProgress(USER)).thenReturn(true);

        boolean active = serviceAt(LocalDate.of(2026, 6, 5)).hasActiveChallenge(USER);

        assertThat(active).isTrue();
        assertThat(ongoing.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("자정 정기 확정 처리 전 3일 연속 미입력 챌린지가 IN_PROGRESS로 남아 있으면 hasActiveChallenge가 VOID로 바꾸고 false를 반환한다")
    void hasActiveChallenge_cancelsAfterThreeMissingDays() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge challenge = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(challenge));
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        boolean active = serviceAt(today).hasActiveChallenge(USER);

        assertThat(active).isFalse();
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(challenge.getInactiveFrom()).isEqualTo(today);
    }

    @Test
    @DisplayName("8일 이상 챌린지에서 어제까지 3일 연속 지출 기록이 없으면 정기 확정이 종료일 전이라도 VOID로 바꾼다")
    void scheduledFinalization_cancelsAfterThreeMissingDays() {
        LocalDate today = LocalDate.of(2026, 6, 5);
        Challenge challenge = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(challenge));
        when(expenseService.hasDayRecord(eq(USER), any(LocalDate.class))).thenReturn(false);

        serviceAt(today).finalizeDueChallenge(USER, 10L, today);

        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(challenge.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(challenge.getInactiveFrom()).isEqualTo(today);
    }

    @Test
    @DisplayName("이미 확정된 챌린지가 정기 확정 대상에 남아 있어도 상태를 다시 계산하지 않는다")
    void scheduledFinalization_isIdempotent() {
        Challenge ended = endedWithId(
                10L, LocalDate.of(2026, 6, 1), 7, 70000, 10000, ChallengeStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ended));

        serviceAt(LocalDate.of(2026, 6, 20))
                .finalizeDueChallenge(USER, 10L, LocalDate.of(2026, 6, 20));

        assertThat(ended.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        verifyNoInteractions(expenseService);
        verify(challengeDayRepository, never()).findByChallenge_Id(anyLong());
    }

    @Test
    @DisplayName("집중 카테고리를 수정하면 챌린지에 저장돼 있던 카테고리가 요청에 담아 보낸 카테고리로 통째로 바뀌고, 바뀐 뒤의 카테고리가 응답에 실려 돌아온다")
    void updateFocusCategories_replacesAll() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.replaceWeakCategories(List.of("배달", "카페"));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        FocusCategoriesResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of("카페", "편의점")));

        assertThat(res.challengeId()).isEqualTo(10L);
        assertThat(res.categories()).containsExactly("카페", "편의점");
    }

    @Test
    @DisplayName("카테고리를 하나도 담지 않은 요청으로 수정하면 집중 카테고리가 전부 해제되고 응답의 카테고리도 비어서 돌아온다 — 하나도 안 고르는 것도 유효한 선택이라 에러가 아니다")
    void updateFocusCategories_clearsWhenEmptyList() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.replaceWeakCategories(List.of("배달", "카페"));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        FocusCategoriesResponse res = serviceAt(LocalDate.of(2026, 6, 5))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of()));

        assertThat(res.categories()).isEmpty();
        assertThat(ch.getWeakCategories()).isEmpty();
    }

    @Test
    @DisplayName("이미 종료된 챌린지의 집중 카테고리를 수정하면 409를 던진다 — 끝난 챌린지의 카테고리는 그 결과를 설명하는 과거 값이라 잠근다")
    void updateFocusCategories_conflictWhenEnded() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.applyResult(ChallengeStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of("카페"))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
    }

    @Test
    @DisplayName("기간이 다 지났는데 결과 화면을 안 열어 미확정으로 남은 챌린지의 집중 카테고리 수정도 409로 거절되며, 이때 카테고리도 챌린지 상태도 그대로 남는다")
    void updateFocusCategories_conflictWhenExpiredWithoutMutatingState() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        ch.replaceWeakCategories(List.of("배달"));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of("카페"))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);

        assertThat(ch.getWeakCategories())
                .extracting(ChallengeWeakCategory::getCategory)
                .containsExactly("배달");
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("챌린지 마지막 날(endDate 당일)의 집중 카테고리 수정은 아직 기간 중이라 정상 처리된다")
    void updateFocusCategories_allowedOnEndDate() {
        Challenge ch = inProgressWithId(10L, LocalDate.of(2026, 6, 1));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        FocusCategoriesResponse res = serviceAt(LocalDate.of(2026, 6, 14))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of("카페")));

        assertThat(res.categories()).containsExactly("카페");
    }

    @Test
    @DisplayName("없는 챌린지의 집중 카테고리를 수정하려 하면 404를 던진다")
    void updateFocusCategories_notFound() {
        when(challengeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .updateFocusCategories(USER, 99L, new FocusCategoriesRequest(List.of("카페"))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 챌린지의 집중 카테고리를 수정하려 하면 403을 던지고 그 챌린지의 카테고리는 그대로 남는다")
    void updateFocusCategories_forbiddenWhenNotOwner() {
        Challenge others = Challenge.builder()
                .userId(2L).durationDays(14).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(280000).dailyLimit(20000).build();
        others.replaceWeakCategories(List.of("배달"));
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5))
                .updateFocusCategories(USER, 10L, new FocusCategoriesRequest(List.of("카페"))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_FORBIDDEN);

        assertThat(others.getWeakCategories())
                .extracting(ChallengeWeakCategory::getCategory)
                .containsExactly("배달");
    }

    @Test
    @DisplayName("종료된 챌린지가 하나도 없으면 추천 조회가 404를 던진다 — 첫 챌린지는 온보딩에서 직접 설정하는 흐름이라 빈 추천을 내리지 않는다")
    void recommendation_notFoundWhenNoEndedChallenge() {
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ChallengeErrorCode.NO_ENDED_CHALLENGE));
    }

    @Test
    @DisplayName("성공으로 끝난 직전 챌린지의 절약액으로 추천 멘트를 만든다")
    void recommendation_successMessageUsesSavedAmount() {
        Challenge prev = endedWithId(30L, LocalDate.of(2026, 6, 1), 30, 400000, 13333, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(prev));
        when(challengeDayRepository.findByChallenge_Id(30L)).thenReturn(List.of(
                ChallengeDay.of(prev, LocalDate.of(2026, 6, 1), 340000, DayStatus.OVER, prev.getDailyLimit())));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("목표보다 60,000원 절약했어요! 이번엔 조금 더 타이트하게 가볼까요? "
                        + "기간은 그대로 30일, 목표는 360,000원으로 줄여서 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("성공한 직전 챌린지의 목표와 실지출 차이가 정확히 0원이면 목표 금액을 정확히 지켰다는 멘트를 만든다")
    void recommendation_successExactBudgetMessage() {
        Challenge prev = endedWithId(30L, LocalDate.of(2026, 6, 1), 1, 100, 100, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(prev));
        when(challengeDayRepository.findByChallenge_Id(30L)).thenReturn(List.of(
                ChallengeDay.of(prev, LocalDate.of(2026, 6, 1), 100, DayStatus.SUCCESS, 100)));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("목표 금액을 정확히 지켰어요! 이번엔 조금 더 타이트하게 가볼까요? "
                        + "기간은 그대로 1일, 목표는 90원으로 줄여서 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("성공 후 추천 목표가 직전 목표와 같거나 더 크면 각각 유지·증가에 맞는 멘트를 만든다")
    void recommendation_successDistinguishesEqualAndHigherBudgets() {
        assertThat(ChallengeService.recommendationMessage(ChallengeStatus.SUCCESS, null, 100, 90, 1, 100))
                .isEqualTo("목표보다 10원 절약했어요! 이번에도 같은 목표로 이어가볼까요? "
                        + "기간은 그대로 1일, 목표는 그대로 100원으로 새 기록에 도전해봐요.");
        assertThat(ChallengeService.recommendationMessage(ChallengeStatus.SUCCESS, null, 100, 90, 1, 120))
                .isEqualTo("목표보다 10원 절약했어요! 이번엔 조금 더 여유 있게 가볼까요? "
                        + "기간은 그대로 1일, 목표는 120원으로 늘려서 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("성공 상태인데 실지출이 목표를 초과한 모순 데이터는 목표를 정확히 지켰다는 멘트로 숨기지 않는다")
    void recommendation_successRejectsNegativeSavedAmount() {
        Challenge prev = endedWithId(30L, LocalDate.of(2026, 6, 1), 1, 100, 100, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(prev));
        when(challengeDayRepository.findByChallenge_Id(30L)).thenReturn(List.of(
                ChallengeDay.of(prev, LocalDate.of(2026, 6, 1), 101, DayStatus.OVER, 100)));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("성공한 챌린지의 실지출이 목표 금액을 초과했습니다.");
    }

    @Test
    @DisplayName("직전 목표가 1원이면 10% 감소 결과인 0원을 추천 멘트에 포함한다")
    void recommendation_allowsZeroBudget() {
        Challenge prev = endedWithId(30L, LocalDate.of(2026, 6, 1), 1, 1, 1, ChallengeStatus.SUCCESS);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(prev));
        when(challengeDayRepository.findByChallenge_Id(30L)).thenReturn(List.of());

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("목표보다 1원 절약했어요! 이번엔 조금 더 타이트하게 가볼까요? "
                        + "기간은 그대로 1일, 목표는 0원으로 줄여서 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("금액 초과로 실패한 직전 챌린지는 직전 목표와 실지출의 중간값을 다음 목표로 추천한다")
    void recommendation_budgetFailUsesMidpointAsNextBudget() {
        Challenge prev = endedWithId(30L, LocalDate.of(2026, 6, 1), 14, 280000, 20000, ChallengeStatus.FAIL);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(prev));
        when(challengeDayRepository.findByChallenge_Id(30L)).thenReturn(List.of(
                ChallengeDay.of(prev, LocalDate.of(2026, 6, 1), 300000, DayStatus.OVER, prev.getDailyLimit())));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("목표보다 20,000원 초과했어요. 이번엔 다시 도전해볼까요? "
                        + "기간은 그대로 14일, 목표는 290,000원으로 늘려서 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("중도 포기는 전체 기간 목표 대비 절약액 대신 중도 포기 사실을 추천 멘트에 넣는다")
    void recommendation_givenUpUsesSeparateMessage() {
        Challenge givenUp = Challenge.builder()
                .userId(USER).durationDays(30).startDate(LocalDate.of(2026, 7, 1))
                .budgetTotal(300000).dailyLimit(10000).build();
        givenUp.giveUp(LocalDate.of(2026, 7, 10));
        ReflectionTestUtils.setField(givenUp, "id", 31L);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(givenUp));
        when(challengeDayRepository.findByChallenge_Id(31L)).thenReturn(List.of(
                ChallengeDay.of(givenUp, LocalDate.of(2026, 7, 1), 4000, DayStatus.SUCCESS, 10000)));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("이번 챌린지는 중도 포기로 끝났어요. "
                        + "기간은 그대로 30일, 목표는 그대로 300,000원으로 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("미입력 자동 취소는 전체 기간 목표 대비 절약액 대신 자동 취소 사실을 추천 멘트에 넣는다")
    void recommendation_voidUsesSeparateMessage() {
        Challenge voided = Challenge.builder()
                .userId(USER).durationDays(30).startDate(LocalDate.of(2026, 7, 1))
                .budgetTotal(300000).dailyLimit(10000).build();
        voided.cancelForMissingInput(LocalDate.of(2026, 7, 3));
        ReflectionTestUtils.setField(voided, "id", 32L);
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(voided));
        when(challengeDayRepository.findByChallenge_Id(32L)).thenReturn(List.of(
                ChallengeDay.of(voided, LocalDate.of(2026, 7, 1), 4000, DayStatus.SUCCESS, 10000)));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getRecommendation(USER);

        assertThat(res.message())
                .isEqualTo("지출 미입력으로 이번 챌린지가 자동 취소됐어요. "
                        + "기간은 그대로 30일, 목표는 그대로 300,000원으로 새 기록에 도전해봐요.");
    }

    @Test
    @DisplayName("기간이 끝났는데 결과 화면을 안 열어 확정 전인 챌린지는 추천 조회가 먼저 확정하고 직전 종료 챌린지로 쓴다")
    void recommendation_finalizesExpiredInProgressFirst() {
        Challenge expired = inProgress(LocalDate.of(2026, 6, 1));
        ReflectionTestUtils.setField(expired, "id", 20L);
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(expired));
        when(challengeDayRepository.findByChallenge_Id(20L)).thenReturn(List.of());
        when(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
                USER, RECOMMENDATION_STATUSES))
                .thenReturn(Optional.of(expired));

        RecommendationResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getRecommendation(USER);

        assertThat(expired.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(res.message())
                .contains("280,000원 절약", "기간은 그대로 14일", "목표는 252,000원");
    }

    @Test
    @DisplayName("결과의 emotionBreakdown은 ExpenseSpendingQuery.periodSpending(챌린지 시작일~종료일)이 채운다 (#64)")
    void result_fillsEmotionBreakdownFromExpenseSpendingQuery() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());
        List<EmotionSpending> emotionBreakdown = List.of(new EmotionSpending(ExpenseEmotion.STRESS, 7_000, 100));
        when(expenseSpendingQuery.periodSpending(USER, ch.getStartDate(), ch.getEndDate()))
                .thenReturn(new PeriodSpending(7_000, emotionBreakdown));

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.emotionBreakdown()).isEqualTo(emotionBreakdown);
    }

    /** 목 환경엔 채번이 없어 id를 직접 박아 둔 진행 중 챌린지 — 응답의 challengeId 확인용. */
    private static Challenge inProgressWithId(Long id, LocalDate start) {
        Challenge c = inProgress(start);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static Challenge endedWithId(Long id, LocalDate start, int durationDays,
                                         int budgetTotal, int dailyLimit, ChallengeStatus result) {
        Challenge c = Challenge.builder()
                .userId(USER).durationDays(durationDays).startDate(start)
                .budgetTotal(budgetTotal).dailyLimit(dailyLimit).build();
        c.applyResult(result);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static Challenge fixedChallengeWithId(Long id, LocalDate start, int durationDays,
                                                   int budgetTotal, int dailyLimit, int fixedDay,
                                                   ChallengeStatus status) {
        Challenge challenge = Challenge.builder()
                .userId(USER)
                .durationDays(durationDays)
                .startDate(start)
                .budgetTotal(budgetTotal)
                .dailyLimit(dailyLimit)
                .resetByPayday(true)
                .paydayDay(fixedDay)
                .build();
        if (status != ChallengeStatus.IN_PROGRESS) {
            challenge.applyResult(status);
        }
        ReflectionTestUtils.setField(challenge, "id", id);
        return challenge;
    }

    private static Challenge inProgress(LocalDate start) {
        return Challenge.builder()
                .userId(USER).durationDays(14).startDate(start)
                .budgetTotal(280000).dailyLimit(20000).build();
    }
}
