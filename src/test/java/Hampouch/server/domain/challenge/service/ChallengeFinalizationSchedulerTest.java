package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository.FinalizationCheckTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeFinalizationSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 20);

    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    ChallengeService challengeService;

    @Test
    @DisplayName("앱 시작 시 자정 작업을 놓친 챌린지를 서울 날짜 기준으로 바로 확인한다")
    void checksDueChallengesAfterStartup() {
        when(challengeRepository.findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler().finalizeDueChallengesAfterStartup();

        verify(challengeRepository).findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(0L), any(Pageable.class));
    }

    @Test
    @DisplayName("정기 확정은 조회된 검사 대상을 ID 순서대로 서비스에 전달한다")
    void finalizesTargetsInOrder() {
        var first = target(10L, 1L);
        var second = target(20L, 2L);
        when(challengeRepository.findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        scheduler().finalizeDueChallenges(TODAY);

        var inOrder = inOrder(challengeService);
        inOrder.verify(challengeService).finalizeDueChallenge(1L, 10L, TODAY);
        inOrder.verify(challengeService).finalizeDueChallenge(2L, 20L, TODAY);
    }

    @Test
    @DisplayName("첫 100건을 처리한 뒤 마지막 ID를 커서로 다음 페이지를 조회한다")
    void continuesFromLastIdAfterFullBatch() {
        List<FinalizationCheckTarget> firstPage = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> target(id, id + 1_000))
                .toList();
        var nextPageTarget = target(101L, 1_101L);
        when(challengeRepository.findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(0L), any(Pageable.class)))
                .thenReturn(firstPage);
        when(challengeRepository.findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(100L), any(Pageable.class)))
                .thenReturn(List.of(nextPageTarget));

        scheduler().finalizeDueChallenges(TODAY);

        verify(challengeRepository).findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(100L), any(Pageable.class));
        verify(challengeService).finalizeDueChallenge(1_101L, 101L, TODAY);
    }

    @Test
    @DisplayName("한 챌린지 확정이 실패해도 같은 실행의 다음 대상은 계속 처리한다")
    void continuesAfterTargetFailure() {
        var first = target(10L, 1L);
        var second = target(20L, 2L);
        when(challengeRepository.findFinalizationCheckTargetsAfter(
                eq(TODAY), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("실패"))
                .when(challengeService).finalizeDueChallenge(1L, 10L, TODAY);

        scheduler().finalizeDueChallenges(TODAY);

        verify(challengeService).finalizeDueChallenge(2L, 20L, TODAY);
    }

    private ChallengeFinalizationScheduler scheduler() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(SEOUL).toInstant(), SEOUL);
        return new ChallengeFinalizationScheduler(challengeRepository, challengeService, clock);
    }

    private FinalizationCheckTarget target(Long challengeId, Long userId) {
        return new FinalizationCheckTarget() {
            @Override
            public Long getChallengeId() {
                return challengeId;
            }

            @Override
            public Long getUserId() {
                return userId;
            }
        };
    }
}
