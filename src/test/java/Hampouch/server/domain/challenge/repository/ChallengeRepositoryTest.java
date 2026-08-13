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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 히스토리 파생 쿼리와 챌린지 테이블 제약(유저당 진행 중 1개 유니크)을 H2에 실제 적용해 검증 —
 * 메서드 이름이 만드는 SQL과 DDL 제약은 목으로는 검증이 안 되고 진짜 DB가 필요하다.
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
        Challenge voided = persist(1L, LocalDate.of(2026, 6, 20), 14, null);
        voided.cancelForMissingInput(LocalDate.of(2026, 6, 23));
        challengeRepository.flush();
        persist(1L, LocalDate.of(2026, 7, 1), 7, null);                       // 진행 중 — 제외돼야 함
        persist(2L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.SUCCESS);    // 남의 것 — 제외돼야 함

        List<Challenge> result = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(1L, ENDED);

        assertThat(result).extracting(Challenge::getId)
                .containsExactly(fail.getId(), success.getId()); // 최근 종료(6/7)가 먼저
    }

    @Test
    @DisplayName("종료일이 같으면 id 내림차순(나중에 만든 것 먼저)으로 정렬이 매번 같게 나온다 — 보조 정렬 기준")
    void historyQuery_tieBreaksByIdDesc() {
        // 정렬 동률 경계를 만들기 위해 종료일이 같은 행 2개를 저장한다.
        Challenge older = persist(1L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.SUCCESS);
        Challenge newer = persist(1L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.FAIL);

        List<Challenge> result = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(1L, ENDED);

        assertThat(result).extracting(Challenge::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("포기한 챌린지는 포기 전날까지만 조회되고 포기한 날부터는 조회되지 않는다")
    void historicalDateQuery_stopsAtEarlyTerminationDate() {
        LocalDate inactiveFrom = LocalDate.of(2026, 6, 4);
        Challenge givenUp = persist(1L, LocalDate.of(2026, 6, 1), 30, null);
        givenUp.giveUp(inactiveFrom);
        challengeRepository.flush();
        persist(2L, LocalDate.of(2026, 6, 1), 30, ChallengeStatus.SUCCESS);

        assertThat(challengeRepository.findContainingDate(1L, inactiveFrom.minusDays(1)))
                .map(Challenge::getId).contains(givenUp.getId());
        assertThat(challengeRepository.findContainingDate(1L, inactiveFrom)).isEmpty();
        assertThat(challengeRepository.findContainingDate(3L, inactiveFrom)).isEmpty();
    }

    @Test
    @DisplayName("포기한 날 새 챌린지를 시작하면 이전 챌린지는 제외되고 새 챌린지만 조회된다")
    void historicalDateQuery_sameDayRestartPicksNewChallenge() {
        LocalDate selectedDate = LocalDate.of(2026, 6, 5);
        Challenge previous = persist(1L, LocalDate.of(2026, 6, 1), 30, null);
        previous.giveUp(selectedDate);
        challengeRepository.flush();

        assertThat(challengeRepository.findContainingDate(1L, selectedDate)).isEmpty();

        Challenge restarted = persist(1L, selectedDate, 7, null);

        assertThat(challengeRepository.findContainingDate(1L, selectedDate))
                .map(Challenge::getId).contains(restarted.getId());
    }

    @Test
    @DisplayName("종료일 다음 날 자동 취소가 확정돼도 날짜 조회 범위는 원래 목표 종료일 뒤로 늘어나지 않는다")
    void historicalDateQuery_neverExtendsPastPlannedEndDate() {
        Challenge autoCancelled = persist(1L, LocalDate.of(2026, 6, 1), 8, null);
        autoCancelled.cancelForMissingInput(LocalDate.of(2026, 6, 9));
        challengeRepository.flush();

        assertThat(challengeRepository.findContainingDate(1L, LocalDate.of(2026, 6, 8)))
                .map(Challenge::getId).contains(autoCancelled.getId());
        assertThat(challengeRepository.findContainingDate(1L, LocalDate.of(2026, 6, 9))).isEmpty();
    }

    @Test
    @DisplayName("같은 유저의 진행 중 챌린지가 이미 있으면 두 번째 진행 중 챌린지 저장을 데이터베이스 유니크 제약이 거절한다 — 서비스의 존재 검사를 동시에 통과한 경쟁 요청을 막는 마지막 방어선")
    void uniqueConstraint_rejectsSecondInProgressForSameUser() {
        persist(1L, LocalDate.of(2026, 6, 1), 7, null);

        assertThatThrownBy(() -> persist(1L, LocalDate.of(2026, 6, 8), 7, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("유저가 다르면 진행 중 챌린지가 나란히 저장된다 — 제약은 유저 단위로만 묶는다")
    void uniqueConstraint_allowsInProgressAcrossUsers() {
        persist(1L, LocalDate.of(2026, 6, 1), 7, null);
        persist(2L, LocalDate.of(2026, 6, 1), 7, null);

        assertThat(challengeRepository.existsInProgress(1L)).isTrue();
        assertThat(challengeRepository.existsInProgress(2L)).isTrue();
    }

    @Test
    @DisplayName("정기 확정 검사 대상 조회는 기간이 종료됐거나 8일 이상이며 어제까지 진행한 날이 3일 이상인 IN_PROGRESS 챌린지만 ID 순서로 반환한다")
    void finalizationCheckQuery_filtersAndOrders() {
        LocalDate today = LocalDate.of(2026, 6, 10);
        Challenge periodEnded = persist(10L, LocalDate.of(2026, 6, 1), 7, null);
        Challenge missingInputCheckTarget = persist(20L, LocalDate.of(2026, 6, 3), 8, null);
        persist(30L, LocalDate.of(2026, 6, 8), 8, null);
        persist(40L, LocalDate.of(2026, 6, 1), 7, ChallengeStatus.SUCCESS);
        persist(50L, LocalDate.of(2026, 6, 8), 7, null);

        List<ChallengeRepository.FinalizationCheckTarget> targets =
                challengeRepository.findFinalizationCheckTargetsAfter(
                        ChallengeStatus.IN_PROGRESS, today, today.minusDays(3), 8, 0L, Pageable.ofSize(100));

        assertThat(targets)
                .extracting(ChallengeRepository.FinalizationCheckTarget::getChallengeId)
                .containsExactly(periodEnded.getId(), missingInputCheckTarget.getId());
        assertThat(targets)
                .extracting(ChallengeRepository.FinalizationCheckTarget::getUserId)
                .containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("최종 종료된 기록 기반 챌린지의 기간만 지출 변경 금지로 판정하고 포기 챌린지는 제외한다")
    void expenseDateLockQuery_distinguishesClosedFromGivenUpChallenge() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate date = LocalDate.of(2026, 6, 3);
        Challenge resultBased = persist(3L, start, 7, ChallengeStatus.SUCCESS);

        assertThat(challengeRepository.isExpenseChangeProhibited(3L, date)).isFalse();

        resultBased.lockExpenseChanges(LocalDateTime.of(2026, 6, 10, 12, 0));
        challengeRepository.flush();
        assertThat(challengeRepository.isExpenseChangeProhibited(3L, date)).isTrue();
        assertThat(challengeRepository.isExpenseChangeProhibited(3L, start.minusDays(1))).isFalse();

        Challenge givenUp = persist(4L, start, 7, null);
        givenUp.giveUp(LocalDate.of(2026, 6, 3));
        challengeRepository.flush();
        assertThat(challengeRepository.isExpenseChangeProhibited(4L, date)).isFalse();
    }

    @Test
    @DisplayName("진행 중 챌린지를 종료 상태로 바꾸면 같은 유저의 새 진행 중 챌린지 저장을 데이터베이스가 받아들인다 — 데이터베이스가 계산 컬럼을 상태 변경에 맞춰 다시 비워 준다")
    void uniqueConstraint_freesSlotAfterFinish() {
        Challenge first = persist(1L, LocalDate.of(2026, 6, 1), 7, null);
        first.applyResult(ChallengeStatus.SUCCESS);
        // 상태 변경 UPDATE를 먼저 DB로 내보낸다 — id가 IDENTITY라 아래 새 행 INSERT는 즉시 나가는데,
        // UPDATE가 그보다 늦으면 DB엔 아직 진행 중 행이 남아 있어 제약에 걸린다(플러시 순서 함정)
        challengeRepository.flush();

        persist(1L, LocalDate.of(2026, 6, 8), 7, null);

        assertThat(challengeRepository.existsInProgress(1L)).isTrue();
    }
}
