package Hampouch.server.domain.minichallenge;

import Hampouch.server.domain.minichallenge.dto.MiniCheckRequest;
import Hampouch.server.domain.minichallenge.dto.MiniCheckResponse;
import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeDayRepository;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeRepository;
import Hampouch.server.domain.minichallenge.service.MiniChallengeService;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@MySqlContainerTest
class MiniChallengeTransactionIntegrationTest {

    private static final Long USER = 57L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MiniChallengeService miniChallengeService;
    @Autowired
    MiniChallengeRepository miniChallengeRepository;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    MiniChallengeDayRepository miniChallengeDayRepository;

    @Test
    @DisplayName("같은 미니·날짜 체크 두 건이 exists 검사를 동시에 통과하지 않고 직렬화돼 둘 다 성공하며 체크 행은 하나만 남는다")
    void concurrentChecksRemainIdempotent() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        MiniChallenge mini = miniChallengeRepository.saveAndFlush(
                MiniChallenge.create(USER, "동시 체크", 7, today));
        CountDownLatch firstExists = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger existsCalls = new AtomicInteger();

        doAnswer(invocation -> {
            Long matchingRows = jdbc.queryForObject(
                    "select count(*) from mini_challenge_day where mini_challenge_id = ? and check_date = ?",
                    Long.class, mini.getId(), today);
            boolean exists = matchingRows != null && matchingRows > 0;
            if (existsCalls.incrementAndGet() == 1) {
                firstExists.countDown();
                await(releaseFirst);
            }
            return exists;
        }).when(miniChallengeDayRepository)
                .existsByMiniChallenge_IdAndCheckDate(mini.getId(), today);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MiniCheckResponse> first = executor.submit(() -> miniChallengeService.check(
                    USER, mini.getId(), new MiniCheckRequest(today, true)));
            boolean firstReachedExists = firstExists.await(5, TimeUnit.SECONDS);
            if (!firstReachedExists && first.isDone()) {
                first.get();
            }
            assertThat(firstReachedExists).isTrue();

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<MiniCheckResponse> second = executor.submit(() -> {
                secondStarted.countDown();
                return miniChallengeService.check(
                        USER, mini.getId(), new MiniCheckRequest(today, true));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

            boolean secondWasBlocked;
            try {
                second.get(500, TimeUnit.MILLISECONDS);
                secondWasBlocked = false;
            } catch (TimeoutException expected) {
                secondWasBlocked = true;
            }

            assertThat(secondWasBlocked).isTrue();
            assertThat(existsCalls.get()).isEqualTo(1);
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).checked()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).checked()).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        List<MiniChallengeDay> rows = miniChallengeDayRepository
                .findByMiniChallenge_IdInAndCheckDateLessThanEqual(List.of(mini.getId()), today);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCheckDate()).isEqualTo(today);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기가 중단됐습니다.", e);
        }
    }
}
