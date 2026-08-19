package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "조회 기간에 걸친 진행 중 챌린지가 지금 어떤 상태인가" 판정. 두 관문을 차례로 지난다 - 기간 겹침, 그리고 예산 페이스.
 * 기준 조회 기간은 2026년 5월 한 달이고, 챌린지 기간은 startDate + durationDays로 정해진다.
 */
@ExtendWith(MockitoExtension.class)
class ChallengeProgressReaderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 20);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);

    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    ExpenseService expenseService;

    // ---------- 기간 겹침 ----------

    @Test
    @DisplayName("챌린지 기간이 조회 기간 안에 완전히 들어가면 겹친 것으로 본다")
    void overlaps_challengeInsidePeriod() {
        givenInProgress(LocalDate.of(2026, 5, 10), 7, 70_000); // 5/10 ~ 5/16
        givenSpent(0);

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    /** 반대 방향. 조회 기간 안에서 시작해 끝나는 챌린지만 보면 이 케이스를 놓친다. */
    @Test
    @DisplayName("챌린지 기간이 조회 기간을 통째로 덮어도 겹친 것으로 본다")
    void overlaps_periodInsideChallenge() {
        givenInProgress(LocalDate.of(2026, 4, 20), 50, 500_000); // 4/20 ~ 6/8
        givenSpent(0);

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    @Test
    @DisplayName("챌린지가 조회 기간 앞뒤로 걸쳐 있으면 겹친 것으로 본다")
    void overlaps_partially() {
        givenInProgress(LocalDate.of(2026, 4, 25), 10, 100_000); // 4/25 ~ 5/4
        givenSpent(0);

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    /**
     * 하루만 맞닿는 자리. 끝이 조회 시작일과 같은 날이면 그 하루는 두 기간 모두에 속하므로 겹침이다.
     * 여기서 미끄러지면 4월 말에 끝난 챌린지가 5월 분석 문구를 바꾸거나, 그 반대가 된다.
     */
    @Test
    @DisplayName("경계에서 하루만 맞닿아도 겹친 것으로 본다")
    void overlaps_onSingleBoundaryDay() {
        givenInProgress(LocalDate.of(2026, 4, 25), 7, 70_000); // 4/25 ~ 5/1
        givenSpent(0);

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    /** 겹치지 않으면 문구가 어차피 안 바뀌므로 지출까지 읽지 않는다. */
    @Test
    @DisplayName("챌린지가 조회 기간 하루 전에 끝났으면 NONE이고, 지출도 읽지 않는다")
    void doesNotOverlap_endedTheDayBefore() {
        givenInProgress(LocalDate.of(2026, 4, 24), 7, 70_000); // 4/24 ~ 4/30

        assertThat(progress()).isEqualTo(ChallengeProgress.NONE);
        verifyNoInteractions(expenseService);
    }

    @Test
    @DisplayName("진행 중 챌린지가 없으면 NONE")
    void doesNotOverlap_noInProgressChallenge() {
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.empty());

        assertThat(progress()).isEqualTo(ChallengeProgress.NONE);
        verifyNoInteractions(expenseService);
    }

    // ---------- 예산 페이스 ----------

    /**
     * 페이스 경계. 5/18 시작 7일 30,000원 챌린지를 5/20에 보면 경과 3일이라 허용치는 30,000 × 3/7 = 12,857.1원이다.
     * 12,857원은 그 안이고 12,858원은 넘는다 - 두 값을 나란히 둬야 경계가 어느 쪽으로 열려 있는지 드러난다.
     *
     * 하루 한도(30,000 / 7 = 4,285원)를 3배 한 12,855원으로 자르면 12,857원이 초과로 뒤집힌다.
     * 나눗셈의 나머지가 버려지기 때문인데, 실제로 예산 안에서 쓴 사람이 초과로 분류되면 안 된다.
     */
    @Test
    @DisplayName("경과일만큼의 예산 안에서 썼으면 ON_TRACK - 나눗셈 나머지로 뒤집히지 않는다")
    void onTrack_atBudgetPace() {
        givenInProgress(LocalDate.of(2026, 5, 18), 7, 30_000);
        givenSpent(12_857);

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    @Test
    @DisplayName("경과일만큼의 예산을 1원이라도 넘겼으면 OVER_PACE")
    void notOnTrack_justOverBudgetPace() {
        givenInProgress(LocalDate.of(2026, 5, 18), 7, 30_000);
        givenSpent(12_858);

        assertThat(progress()).isEqualTo(ChallengeProgress.OVER_PACE);
    }

    /**
     * 총 예산은 아직 안 넘겼지만 사흘 만에 거의 다 태운 경우.
     * 남은 예산으로만 보면 "아직 안 넘었으니 잘 지키는 중"이 되는데, 이 페이스로는 끝까지 갈 수 없다.
     */
    @Test
    @DisplayName("총 예산 안이어도 초반에 몰아 썼으면 OVER_PACE")
    void notOnTrack_burnedBudgetEarly() {
        givenInProgress(LocalDate.of(2026, 5, 18), 7, 30_000);
        givenSpent(29_000);

        assertThat(progress()).isEqualTo(ChallengeProgress.OVER_PACE);
    }

    /**
     * 날짜 고정 챌린지는 시작일이 미래인 채로 진행 중일 수 있다.
     * 경과일이 0이면 아직 쓸 기회조차 없었으므로 지출을 읽지 않고 지키는 중으로 본다.
     */
    @Test
    @DisplayName("아직 시작하지 않은 챌린지는 지출을 읽지 않고 ON_TRACK으로 본다")
    void onTrack_beforeChallengeStarts() {
        givenInProgress(LocalDate.of(2026, 5, 25), 7, 70_000); // 5/25 ~ 5/31

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
        verifyNoInteractions(expenseService);
    }

    /** 페이스는 조회 기간이 아니라 챌린지 시작일부터 오늘까지로 잰다. */
    @Test
    @DisplayName("지출 조회 구간은 챌린지 시작일부터 오늘까지다")
    void readsSpendingFromChallengeStartThroughToday() {
        givenInProgress(LocalDate.of(2026, 5, 18), 7, 30_000);
        when(expenseService.getDailySpending(USER, LocalDate.of(2026, 5, 18), TODAY))
                .thenReturn(Map.of(LocalDate.of(2026, 5, 19), 1_000L));

        assertThat(progress()).isEqualTo(ChallengeProgress.ON_TRACK);
    }

    private void givenInProgress(LocalDate startDate, int durationDays, int budgetTotal) {
        Challenge challenge = Challenge.builder()
                .userId(USER)
                .durationDays(durationDays)
                .startDate(startDate)
                .budgetTotal(budgetTotal)
                .dailyLimit(ChallengeCalculator.dailyLimit(budgetTotal, durationDays))
                .build();
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(challenge));
    }

    /** 페이스 판정의 입력은 합계뿐이라 날짜별로 어떻게 흩어졌는지는 상관이 없다. */
    private void givenSpent(long totalAmount) {
        when(expenseService.getDailySpending(eq(USER), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Map.of(LocalDate.of(2026, 5, 19), totalAmount));
    }

    private ChallengeProgress progress() {
        Clock clock = Clock.fixed(TODAY.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new ChallengeProgressReader(challengeRepository, expenseService, clock)
                .overlappingChallengeProgress(USER, PERIOD_START, PERIOD_END);
    }
}
