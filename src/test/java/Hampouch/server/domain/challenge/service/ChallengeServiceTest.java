package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
        when(challengeRepository.existsByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS)).thenReturn(false);
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
        when(challengeRepository.existsByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS)).thenReturn(true);
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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
        when(challengeRepository.existsByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS)).thenReturn(false);
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
        Challenge others = Challenge.create(2L, 14, LocalDate.of(2026, 6, 1), 280000, 20000, false, null);
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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
        when(challengeRepository.findByUserIdAndStatus(USER, ChallengeStatus.IN_PROGRESS))
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

    /** 14일 / 목표 280000 / dailyLimit 20000, userId=1 인 진행 중 챌린지. */
    private static Challenge inProgress(LocalDate start) {
        return Challenge.create(USER, 14, start, 280000, 20000, false, null);
    }
}
