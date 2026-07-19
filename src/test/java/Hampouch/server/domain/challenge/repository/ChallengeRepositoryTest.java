package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 히스토리(#4) 파생 쿼리를 H2에 실제 적용해 검증 — 상태 In 필터·endDate 내림차순 정렬은
 * 메서드 이름이 만드는 SQL의 몫이라 목으로는 검증이 안 되고 진짜 DB가 필요하다.
 * (@CreatedDate 위해 Clock·Auditing 설정 import — ChallengeDayRepositoryTest와 동일)
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class ChallengeRepositoryTest {

    private static final List<ChallengeStatus> ENDED = List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL);

    @Autowired
    ChallengeRepository challengeRepository;

    /** userId의 챌린지를 저장하고, result가 있으면 그 상태로 종료시킨다(null이면 IN_PROGRESS 유지). */
    private Challenge persist(Long userId, LocalDate start, int durationDays, ChallengeStatus result) {
        Challenge c = Challenge.builder()
                .userId(userId).durationDays(durationDays).startDate(start)
                .budgetTotal(durationDays * 10000).dailyLimit(10000).build();
        if (result != null) {
            c.applyResult(result);
        }
        return challengeRepository.save(c);
    }

    @Test
    @DisplayName("히스토리 쿼리는 그 유저의 종료(SUCCESS/FAIL)된 챌린지만 최근 종료 순으로 준다 — 진행 중·남의 것 제외")
    void historyQuery_filtersAndOrders() {
        Challenge fail = persist(1L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.FAIL);      // 종료 6/7
        Challenge success = persist(1L, LocalDate.of(2026, 5, 1), 14, ChallengeStatus.SUCCESS); // 종료 5/14
        persist(1L, LocalDate.of(2026, 7, 1), 7, null);                       // 진행 중 — 제외돼야 함
        persist(2L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.SUCCESS);    // 남의 것 — 제외돼야 함

        List<Challenge> result = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(1L, ENDED);

        assertThat(result).extracting(Challenge::getId)
                .containsExactly(fail.getId(), success.getId()); // 최근 종료(6/7)가 먼저
    }

    @Test
    @DisplayName("종료일이 같으면 id 내림차순(나중에 만든 것 먼저)으로 정렬이 매번 같게 나온다 — 보조 정렬 기준")
    void historyQuery_tieBreaksByIdDesc() {
        // 같은 기간의 종료 챌린지 2개 — 동시 진행 1개 규칙은 서비스 검증이라 DB에는 얼마든지 공존 가능
        Challenge older = persist(1L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.SUCCESS);
        Challenge newer = persist(1L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.FAIL);

        List<Challenge> result = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(1L, ENDED);

        assertThat(result).extracting(Challenge::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("직전 종료 챌린지 조회는 종료일이 아니라 생성 순서를 따른다 — 일찍 포기해 종료일만 미래로 남은 옛 챌린지가 나중에 완주한 챌린지를 이기지 못한다")
    void latestEndedQuery_ordersByCreationNotEndDate() {
        // 먼저 만든 30일짜리를 이틀 만에 포기 — endDate(6/30)는 원래 목표 기간 그대로 미래로 남는다(중도포기 명세)
        Challenge givenUpFirst = persist(1L, LocalDate.of(2026, 6, 1), 30, ChallengeStatus.FAIL);
        // 그 뒤에 만든 7일짜리를 완주 — endDate(6/11)는 포기 챌린지보다 이르다
        Challenge finishedLater = persist(1L, LocalDate.of(2026, 6, 5), 7, ChallengeStatus.SUCCESS);
        persist(1L, LocalDate.of(2026, 7, 1), 7, null);                    // 진행 중 — 제외돼야 함
        persist(2L, LocalDate.of(2026, 6, 20), 7, ChallengeStatus.SUCCESS); // 남의 것 — 제외돼야 함

        var latest = challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(1L, ENDED);

        // endDate 내림차순이었다면 givenUpFirst(6/30)가 잡혔을 상황 — 생성순이라 나중에 만든 완주가 직전이다
        assertThat(latest).map(Challenge::getId).contains(finishedLater.getId());
        assertThat(givenUpFirst.getEndDate()).isAfter(finishedLater.getEndDate()); // 함정 전제가 실제로 성립하는지 고정
    }

    @Test
    @DisplayName("종료된 챌린지가 하나도 없으면 직전 종료 챌린지 조회는 빈 값을 준다")
    void latestEndedQuery_emptyWhenNothingEnded() {
        persist(1L, LocalDate.of(2026, 7, 1), 7, null); // 진행 중뿐

        assertThat(challengeRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(1L, ENDED)).isEmpty();
    }
}
