package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 조회 기간과 진행 중 챌린지의 겹침 판정. 판정 로직이 여기 있으므로 경계도 여기서 못박는다
 * (호출자인 ExpenseAnalysisService는 이 값이 문구를 바꾸는지만 확인한다).
 *
 * 기준 조회 기간은 2026년 5월 한 달이고, 챌린지는 startDate + durationDays로 기간이 정해진다.
 */
@ExtendWith(MockitoExtension.class)
class ChallengeProgressReaderTest {

    private static final Long USER = 1L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);

    @Mock
    ChallengeRepository challengeRepository;

    @Test
    @DisplayName("챌린지 기간이 조회 기간 안에 완전히 들어가면 겹친 것으로 본다")
    void overlaps_challengeInsidePeriod() {
        givenInProgress(LocalDate.of(2026, 5, 10), 7); // 5/10 ~ 5/16

        assertThat(overlapping()).isTrue();
    }

    /** 반대 방향. 30일 조회 안에서 시작해 끝나는 챌린지만 보면 이 케이스를 놓친다. */
    @Test
    @DisplayName("챌린지 기간이 조회 기간을 통째로 덮어도 겹친 것으로 본다")
    void overlaps_periodInsideChallenge() {
        givenInProgress(LocalDate.of(2026, 4, 20), 50); // 4/20 ~ 6/8

        assertThat(overlapping()).isTrue();
    }

    @Test
    @DisplayName("챌린지가 조회 기간 앞뒤로 걸쳐 있으면 겹친 것으로 본다")
    void overlaps_partially() {
        givenInProgress(LocalDate.of(2026, 4, 25), 10); // 4/25 ~ 5/4

        assertThat(overlapping()).isTrue();
    }

    /**
     * 하루만 맞닿는 자리. 끝이 조회 시작일과 같은 날이면 그 하루는 두 기간 모두에 속하므로 겹침이다.
     * 여기서 미끄러지면 4월 말에 끝난 챌린지가 5월 분석 문구를 바꾸거나, 그 반대가 된다.
     */
    @Test
    @DisplayName("경계에서 하루만 맞닿아도 겹친 것으로 본다")
    void overlaps_onSingleBoundaryDay() {
        givenInProgress(LocalDate.of(2026, 4, 25), 7); // 4/25 ~ 5/1

        assertThat(overlapping()).isTrue();
    }

    @Test
    @DisplayName("챌린지가 조회 기간 하루 전에 끝났으면 겹치지 않는다")
    void doesNotOverlap_endedTheDayBefore() {
        givenInProgress(LocalDate.of(2026, 4, 24), 7); // 4/24 ~ 4/30

        assertThat(overlapping()).isFalse();
    }

    @Test
    @DisplayName("진행 중 챌린지가 없으면 겹치지 않는다")
    void doesNotOverlap_noInProgressChallenge() {
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.empty());

        assertThat(overlapping()).isFalse();
    }

    private void givenInProgress(LocalDate startDate, int durationDays) {
        Challenge challenge = Challenge.builder()
                .userId(USER)
                .durationDays(durationDays)
                .startDate(startDate)
                .budgetTotal(durationDays * 10_000)
                .dailyLimit(10_000)
                .build();
        when(challengeRepository.findInProgress(USER)).thenReturn(Optional.of(challenge));
    }

    private boolean overlapping() {
        return new ChallengeProgressReader(challengeRepository)
                .hasInProgressChallengeOverlapping(USER, PERIOD_START, PERIOD_END);
    }
}
