package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 서비스 상태 전이·검증 (S5~S7, 409/404). 리포지토리는 Mockito 목 — DB 불필요.
 */
@ExtendWith(MockitoExtension.class)
class ChallengeServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER = 1L;

    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    ChallengeDayRepository challengeDayRepository;

    private ChallengeService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new ChallengeService(challengeRepository, challengeDayRepository, clock);
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
    @DisplayName("이미 진행 중인 챌린지가 있으면 생성이 409로 거절된다 (S6)")
    void create_conflictWhenInProgressExists() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(true);
        var req = new CreateChallengeRequest(14, 280000, LocalDate.of(2026, 6, 1), false, null, null);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 1)).create(USER, req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("종료일이 지나기 전에 결과를 요청하면 409를 던진다 (S5)")
    void result_conflictWhileInProgress() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 10)).getResult(USER, 10L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("종료일이 지났고 전일 성공이면 결과 조회 때 SUCCESS로 확정 저장된다")
    void result_finalizesSuccessAfterEnd() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 10000, DayStatus.SUCCESS),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 2), 12000, DayStatus.SUCCESS)));

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.SUCCESS); // 엔티티에 확정 저장
        assertThat(res.summary().successDays()).isEqualTo(14); // 기록 2일 + 미입력 12일(0원=SUCCESS 간주, 0630 확정)
    }

    @Test
    @DisplayName("챌린지 기간 밖 날짜로 일별 입력하면 400을 던진다 (S7)")
    void upsertDay_outOfRange() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        var req = new DayUpsertRequest(LocalDate.of(2026, 6, 20), 5000, null, null);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).upsertDay(USER, 10L, req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("같은 날짜로 다시 입력하면 기존 행을 덮어쓰고 판정도 새 금액 기준으로 바뀐다 (S7 upsert)")
    void upsertDay_overwritesExisting() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit = 280000/14 = 20000
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 1000, DayStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));

        var req = new DayUpsertRequest(date, 99999, null, null); // 한도 초과로 변경
        DayUpsertResponse res = serviceAt(LocalDate.of(2026, 6, 5)).upsertDay(USER, 10L, req);

        assertThat(res.spentAmount()).isEqualTo(99999);
        assertThat(res.status()).isEqualTo(DayStatus.OVER);
        assertThat(existing.getSpentAmount()).isEqualTo(99999); // 기존 행이 덮어써짐
    }

    @Test
    @DisplayName("진행 중 챌린지가 없으면 현황 조회가 404를 던진다")
    void current_notFound() {
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).getCurrent(USER))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("기록이 한 건도 없어도 종료일이 지나면 전일 미입력=0원=성공으로 SUCCESS 확정된다 (0714 PM)")
    void result_finalizesSuccessWhenNoRecords() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14, dailyLimit 20000
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of());

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(res.summary().successDays()).isEqualTo(14);
        assertThat(res.summary().savedAmount()).isEqualTo(280000); // 14일 × 한도 전액(20000)
        assertThat(res.summary().actualSpent()).isZero();
    }

    @Test
    @DisplayName("SUCCESS로 확정된 뒤라도 기간 내 지출을 초과로 수정하면 결과가 FAIL로 재계산된다 (0714 PM)")
    void upsertDay_recomputesFinalizedStatus() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, dailyLimit 20000
        ch.applyResult(ChallengeStatus.SUCCESS); // 이미 SUCCESS로 확정된 챌린지
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 1000, DayStatus.SUCCESS);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(existing));

        serviceAt(LocalDate.of(2026, 6, 20)).upsertDay(USER, 10L, new DayUpsertRequest(date, 99999, null, null));

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL); // 초과 1일 생김 → 재계산으로 뒤집힘
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
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 5);
        ChallengeDay todayRow = ChallengeDay.of(ch, today, 15000, DayStatus.OVER);
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of(todayRow));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any()))
                .thenReturn(Optional.of(todayRow));

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.consumption().usageRate()).isEqualTo(0.75);
        assertThat(res.consumption().character()).isEqualTo(ConsumptionCharacter.SKINNY);
        assertThat(res.consumption().alertLevel()).isEqualTo(AlertLevel.DANGER);
        assertThat(res.consumption().todayRemaining()).isEqualTo(5000);
        assertThat(res.adjustment().maxCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("집중 카테고리를 중복으로 보내면 중복이 제거되어 저장된다 (유니크 제약 위반 500 방지)")
    void create_dedupsWeakCategories() {
        when(challengeRepository.existsInProgress(USER)).thenReturn(false);
        ArgumentCaptor<Challenge> captor = ArgumentCaptor.forClass(Challenge.class);
        when(challengeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateChallengeRequest(14, 280000, LocalDate.of(2026, 6, 1),
                false, null, List.of("카페", "카페", "배달"));
        serviceAt(LocalDate.of(2026, 6, 1)).create(USER, req);

        assertThat(captor.getValue().getWeakCategories()).hasSize(2); // 카페 중복 1건 제거 → 카페, 배달
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
    @DisplayName("마지막 3일 연속 초과면 경고 카드(GOAL_TOO_TIGHT)가 노출된다 — 오늘도 위험 상태인 기본 케이스")
    void current_warningCardGoalTooTight() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 5);
        ChallengeDay d3 = ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 25000, DayStatus.OVER);
        ChallengeDay d4 = ChallengeDay.of(ch, LocalDate.of(2026, 6, 4), 30000, DayStatus.OVER);
        ChallengeDay d5 = ChallengeDay.of(ch, today, 25000, DayStatus.OVER); // 오늘 사용률 1.25 → DANGER
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of(d3, d4, d5));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any())).thenReturn(Optional.of(d5));

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.warningCards()).containsExactly(WarningCard.GOAL_TOO_TIGHT);
    }

    @Test
    @DisplayName("3일 연속 초과면 오늘 사용률이 위험이 아니어도 경고 카드가 뜬다 — '오늘도 위험할 것'을 추가 조건으로 걸지 않기로 한 0713 결정")
    void current_warningCardDecoupledFromAlertLevel() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 6);
        // 6/3~6/5 연속 초과, 오늘(6/6)은 기록 없음 → 오늘 사용률 0 → alertLevel=NONE
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 25000, DayStatus.OVER),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 4), 30000, DayStatus.OVER),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 5), 25000, DayStatus.OVER)));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any())).thenReturn(Optional.empty());

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.consumption().alertLevel()).isEqualTo(AlertLevel.NONE); // 오늘 위험 아님
        assertThat(res.warningCards()).containsExactly(WarningCard.GOAL_TOO_TIGHT); // 그래도 3일 연속이라 카드는 뜸
    }

    @Test
    @DisplayName("3일 연속 초과했어도 기록 없는 날이 하루 지나가면(미입력=성공 취급) 연속이 끊겨 카드가 사라진다 (0714)")
    void current_warningCardClearedByUnrecordedDay() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 6); // 6/5는 미기록으로 하루 통째 경과, 오늘도 기록 없음
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 2), 25000, DayStatus.OVER),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 30000, DayStatus.OVER),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 4), 25000, DayStatus.OVER)));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any())).thenReturn(Optional.empty());

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.warningCards()).isEmpty(); // 6/5 미기록=성공이 3연속을 끊음
    }

    @Test
    @DisplayName("홈 집계도 미입력일을 0원=성공으로 포함하되, 오늘은 기록을 보내기 전이면 제외한다 (0714 — 결과 화면과 동일 규칙)")
    void current_progressFillsUnrecordedAsSuccess() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // dailyLimit 20000
        LocalDate today = LocalDate.of(2026, 6, 5); // 6/1~6/4 미기록, 오늘도 기록 없음
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(any())).thenReturn(List.of());
        when(challengeDayRepository.findByChallenge_IdAndDayDate(any(), any())).thenReturn(Optional.empty());

        CurrentChallengeResponse res = serviceAt(today).getCurrent(USER);

        assertThat(res.progress().successDays()).isEqualTo(4);          // 6/1~6/4 미기록=성공
        assertThat(res.progress().savedAmountSoFar()).isEqualTo(80000); // 4일 × 한도 전액
        assertThat(res.progress().currentStreak()).isEqualTo(4);
        assertThat(res.warningCards()).isEmpty();
    }

    @Test
    @DisplayName("종료일이 지났고 초과일이 하루라도 있으면 결과가 FAIL로 확정 저장된다")
    void result_finalizesFailAfterEnd() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // endDate 2026-06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_Id(10L)).thenReturn(List.of(
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 10000, DayStatus.SUCCESS),
                ChallengeDay.of(ch, LocalDate.of(2026, 6, 2), 99999, DayStatus.OVER)));

        ResultResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getResult(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL); // 엔티티에 확정 저장
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
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        CalendarResponse res = serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 2026, 8);

        assertThat(res.days()).isEmpty();
    }

    @Test
    @DisplayName("달과 챌린지 기간이 부분만 겹치면 기간 밖 날짜는 응답에 섞이지 않는다 (달∩기간 교집합)")
    void calendar_returnsOnlyDaysWithinPeriod() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDateBetween(
                10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 14)))
                .thenReturn(List.of(ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 5000, DayStatus.SUCCESS)));

        CalendarResponse res = serviceAt(LocalDate.of(2026, 6, 5)).getCalendar(USER, 10L, 2026, 6);

        assertThat(res.days()).hasSize(1);
        assertThat(res.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    @DisplayName("지난 챌린지 리스트는 리포지토리가 준 최근 종료 순서를 유지하고, 금액 요약(actualSpent·savedAmount)은 결과 화면과 같은 미입력일=0원=성공 규칙으로 챌린지별 따로 계산된다")
    void history_aggregatesPerChallenge() {
        // 12번: 6/1~6/7(7일, 한도 10000), 6/3에 15000 초과 기록 1건 → FAIL로 종료된 챌린지
        Challenge c12 = endedWithId(12L, LocalDate.of(2026, 6, 1), 7, 70000, 10000, ChallengeStatus.FAIL);
        // 8번: 5/1~5/14(14일, 한도 20000), 기록 0건 → 전일 미입력=성공으로 SUCCESS 종료된 챌린지
        Challenge c8 = endedWithId(8L, LocalDate.of(2026, 5, 1), 14, 280000, 20000, ChallengeStatus.SUCCESS);
        when(challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                USER, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL)))
                .thenReturn(List.of(c12, c8)); // 최근 종료(6/7)가 먼저 — 정렬은 리포지토리 쿼리 몫
        when(challengeDayRepository.findByChallenge_IdIn(List.of(12L, 8L)))
                .thenReturn(List.of(ChallengeDay.of(c12, LocalDate.of(2026, 6, 3), 15000, DayStatus.OVER)));

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(res.items()).hasSize(2);
        var first = res.items().getFirst();
        assertThat(first.challengeId()).isEqualTo(12L);
        assertThat(first.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(first.actualSpent()).isEqualTo(15000);
        assertThat(first.savedAmount()).isEqualTo(60000); // 미입력 6일 × 한도 전액(10000), 초과일 절약분은 0
        var second = res.items().get(1);
        assertThat(second.challengeId()).isEqualTo(8L);
        assertThat(second.status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(second.actualSpent()).isZero();      // 12번의 기록이 8번 집계에 새어 들지 않는다
        assertThat(second.savedAmount()).isEqualTo(280000); // 14일 × 20000 — 기록 0건이어도 한도 전액 절약
        assertThat(second.budgetTotal()).isEqualTo(280000);
        assertThat(second.durationDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("종료된 챌린지가 없으면 빈 리스트를 주고, 일자 기록 조회 쿼리는 아예 나가지 않는다")
    void history_emptyWhenNothingEnded() {
        when(challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                USER, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL)))
                .thenReturn(List.of());

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(res.items()).isEmpty();
        verify(challengeDayRepository, never()).findByChallenge_IdIn(any());
    }

    @Test
    @DisplayName("기간이 끝났는데 결과 화면을 안 열어 IN_PROGRESS로 남은 챌린지는 히스토리 조회 시점에 확정돼 리스트에 실린다 — 확정은 결과 화면과 같은 lazy 규칙")
    void history_finalizesExpiredInProgress() {
        // 6/20~6/26에 만료됐지만 getResult를 안 불러 IN_PROGRESS로 남은 챌린지 — 기록 0건이라 미입력=성공 규칙으로 SUCCESS가 돼야 한다
        Challenge expired = Challenge.builder()
                .userId(USER).durationDays(7).startDate(LocalDate.of(2026, 6, 20))
                .budgetTotal(70000).dailyLimit(10000).build();
        ReflectionTestUtils.setField(expired, "id", 20L);
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(expired));
        when(challengeDayRepository.findByChallenge_Id(20L)).thenReturn(List.of());
        // 확정 후 재조회 — 실제 DB에선 플러시로 잡히는 것을 목으로 흉내
        when(challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                USER, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL)))
                .thenReturn(List.of(expired));
        when(challengeDayRepository.findByChallenge_IdIn(List.of(20L))).thenReturn(List.of());

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(expired.getStatus()).isEqualTo(ChallengeStatus.SUCCESS); // 조회가 확정을 남김
        assertThat(res.items().getFirst().status()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(res.items().getFirst().savedAmount()).isEqualTo(70000); // 7일 × 한도 전액
    }

    @Test
    @DisplayName("아직 기간 중인 진행 챌린지는 히스토리 조회가 건드리지 않는다 — 마지막 날까지는 결과 미확정이 정상")
    void history_doesNotFinalizeOngoing() {
        Challenge ongoing = inProgress(LocalDate.of(2026, 7, 10)); // 7/10~7/23, 조회일 7/17
        when(challengeRepository.findInProgress(USER))
                .thenReturn(Optional.of(ongoing));
        when(challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                USER, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL)))
                .thenReturn(List.of());

        ChallengeHistoryResponse res = serviceAt(LocalDate.of(2026, 7, 17)).getHistory(USER);

        assertThat(ongoing.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS); // 확정 안 됨
        assertThat(res.items()).isEmpty();
    }

    @Test
    @DisplayName("진행 중 챌린지를 포기하면 즉시 FAIL로 확정되고 종료 사유(GIVEN_UP)가 남는다 — 종료일(endDate)은 원래 목표 기간 그대로 남는다")
    void giveUp_finalizesFailAsDeclared() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        GiveUpResponse res = serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 10L);

        assertThat(res.challengeId()).isEqualTo(10L);
        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);        // 엔티티에 확정 저장(더티 체킹)
        assertThat(ch.getEndReason()).isEqualTo(EndReason.GIVEN_UP);       // 유저 선언 표식 — 재계산 제외 근거
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
        others.applyResult(ChallengeStatus.SUCCESS); // 남의 것 + 이미 종료 — 403이 409를 이겨야 한다
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 5)).giveUp(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_FORBIDDEN);
    }

    @Test
    @DisplayName("기간이 다 지났는데 결과 화면을 안 열어 미확정(IN_PROGRESS)으로 남은 챌린지의 포기는 409로 거절되며, 이때 giveUp은 챌린지 상태를 바꾸지 않는다 — 만료 확정(IN_PROGRESS를 SUCCESS나 FAIL로)을 한 뒤 409를 던지면 트랜잭션 롤백으로 그 확정까지 되돌아가 DB엔 IN_PROGRESS로 남으므로, 확정은 결과·히스토리 조회에 맡긴다. 기간을 다 채워 성공했어야 할 챌린지가 뒤늦은 그만두기 탭 한 번으로 실패가 되지 않는다는 결과는 그대로 지켜진다")
    void giveUp_conflictsWhenExpiredWithoutMutatingState() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, 결과 화면을 안 열어 만료 후에도 IN_PROGRESS
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 20)).giveUp(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);

        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS); // giveUp이 상태를 바꾸지 않음 — 만료 확정은 조회 경로 몫
        assertThat(ch.getEndReason()).isNull();                            // 포기 표식도 남지 않음(거절됐으므로)
        verify(challengeDayRepository, never()).findByChallenge_Id(any());  // 만료 확정용 일별 기록 조회 자체가 나가지 않는다
    }

    @Test
    @DisplayName("챌린지 마지막 날(endDate 당일)의 포기는 아직 기간 중이라 정상 처리된다 — 만료에 따른 자동 확정은 endDate가 지난 뒤에야 일어난다")
    void giveUp_allowedOnEndDate() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));

        GiveUpResponse res = serviceAt(LocalDate.of(2026, 6, 14)).giveUp(USER, 10L);

        assertThat(res.status()).isEqualTo(ChallengeStatus.FAIL);
        assertThat(ch.getEndReason()).isEqualTo(EndReason.GIVEN_UP);
    }

    @Test
    @DisplayName("포기한 챌린지의 기간 내 지출을 전부 성공으로 고쳐도 챌린지 전체 결과(FAIL)가 SUCCESS로 되살아나지 않는다 — 그날그날의 금액·판정 수정은 반영되며, '종료 뒤 지출을 고치면 전체 결과도 다시 계산한다'는 규칙에서 포기 챌린지만 빠진다")
    void upsertDay_doesNotResurrectGivenUpFail() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1)); // 06-01~06-14, dailyLimit 20000
        ch.giveUp(); // FAIL + GIVEN_UP — 유일한 기록이 초과(아래)여도 포기가 선행된 상황
        LocalDate date = LocalDate.of(2026, 6, 3);
        ChallengeDay existing = ChallengeDay.of(ch, date, 99999, DayStatus.OVER);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(challengeDayRepository.findByChallenge_IdAndDayDate(10L, date)).thenReturn(Optional.of(existing));

        serviceAt(LocalDate.of(2026, 6, 10)).upsertDay(USER, 10L, new DayUpsertRequest(date, 1000, null, null));

        assertThat(existing.getSpentAmount()).isEqualTo(1000);            // 수정은 반영(0711 "종료 후 자유 수정")
        assertThat(ch.getStatus()).isEqualTo(ChallengeStatus.FAIL);       // 기록상 전원 성공이 됐어도 안 뒤집힘
        verify(challengeDayRepository, never()).findByChallenge_Id(any()); // 재계산용 전체 조회 자체가 안 나간다
    }

    @Test
    @DisplayName("Challenge 객체의 giveUp 메서드를 서비스 검사를 거치지 않고 이미 끝난 챌린지에 직접 호출하면, 서버 버그로 보고 IllegalStateException이 터진다 — 끝난 챌린지에 또 누르는 클라이언트 실수는 서비스가 409로 먼저 걸러내므로, 이 예외까지 왔다면 검사를 건너뛰고 호출한 서버 코드 실수라는 뜻")
    void giveUp_entityRejectsNonInProgress() {
        Challenge ch = inProgress(LocalDate.of(2026, 6, 1));
        ch.applyResult(ChallengeStatus.SUCCESS);

        assertThatThrownBy(ch::giveUp).isInstanceOf(IllegalStateException.class);
    }

    /**
     * result(SUCCESS/FAIL)로 종료된 유저 1의 챌린지. 목 리포지토리 환경이라 진짜 채번이 없어
     * id만 리플렉션으로 주입한다(미니 서비스 테스트의 miniWithId와 같은 관례) —
     * getHistory가 id로 일자 기록을 나누므로 id 없이는 집계를 못 한다.
     */
    private static Challenge endedWithId(Long id, LocalDate start, int durationDays,
                                         int budgetTotal, int dailyLimit, ChallengeStatus result) {
        Challenge c = Challenge.builder()
                .userId(USER).durationDays(durationDays).startDate(start)
                .budgetTotal(budgetTotal).dailyLimit(dailyLimit).build();
        c.applyResult(result);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    /** 14일 / 목표 280000 / dailyLimit 20000, userId=1 인 진행 중 챌린지. */
    private static Challenge inProgress(LocalDate start) {
        return Challenge.builder()
                .userId(USER).durationDays(14).startDate(start)
                .budgetTotal(280000).dailyLimit(20000).build();
    }
}
