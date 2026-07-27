package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파생 쿼리 + 유니크 제약을 H2에 실제 적용해 검증. (@CreatedDate 위해 Clock·Auditing 설정 import)
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class ChallengeDayRepositoryTest {

    @Autowired
    ChallengeRepository challengeRepository;
    @Autowired
    ChallengeDayRepository dayRepository;

    private Challenge persistChallenge() {
        return challengeRepository.save(Challenge.builder()
                .userId(1L).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
    }

    @Test
    @DisplayName("메서드 이름 파생 쿼리들이 의도한 행을 찾는다 — 유저+상태 exists·find, 챌린지별 find(단일 날짜·기간 between)")
    void derivedQueries() {
        Challenge ch = persistChallenge();

        // 상태 고정판(default 메서드) 경유로 검증 — 서비스가 실제로 쓰는 공개 통로이자, 감싼 파생 쿼리까지 함께 돈다
        assertThat(challengeRepository.existsInProgress(1L)).isTrue();
        assertThat(challengeRepository.findInProgress(1L)).isPresent();

        dayRepository.save(ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 8000, DayStatus.SUCCESS));
        dayRepository.save(ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 12000, DayStatus.OVER));

        assertThat(dayRepository.findByChallenge_IdAndDayDate(ch.getId(), LocalDate.of(2026, 6, 1))).isPresent();
        assertThat(dayRepository.findByChallenge_Id(ch.getId())).hasSize(2);
        assertThat(dayRepository.findByChallenge_IdAndDayDateBetween(
                ch.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2))).hasSize(1);
    }

    @Test
    @DisplayName("여러 챌린지의 일자 기록을 in절 한 번으로 모두 가져온다 — 히스토리 집계의 N+1 방지 쿼리")
    void findByChallengeIdIn() {
        Challenge ch1 = persistChallenge();
        // 두 번째는 지난(종료) 챌린지로 — 히스토리 집계 시나리오 그대로이고, 유저당 진행 중 1개 유니크 제약과도 맞는다
        Challenge past = Challenge.builder()
                .userId(1L).durationDays(7).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(70000).dailyLimit(10000).build();
        past.applyResult(ChallengeStatus.SUCCESS);
        Challenge ch2 = challengeRepository.save(past);
        dayRepository.save(ChallengeDay.of(ch1, LocalDate.of(2026, 6, 1), 8000, DayStatus.SUCCESS));
        dayRepository.save(ChallengeDay.of(ch1, LocalDate.of(2026, 6, 2), 12000, DayStatus.OVER));
        dayRepository.save(ChallengeDay.of(ch2, LocalDate.of(2026, 5, 3), 5000, DayStatus.SUCCESS));

        assertThat(dayRepository.findByChallenge_IdIn(java.util.List.of(ch1.getId(), ch2.getId()))).hasSize(3);
        assertThat(dayRepository.findByChallenge_IdIn(java.util.List.of(ch2.getId()))).hasSize(1);
    }

    @Test
    @DisplayName("같은 챌린지의 같은 날짜로 두 번 저장하면 유니크 제약 위반이 터진다 — 하루 한 행 보장")
    void uniqueConstraintOnDuplicateDay() {
        Challenge ch = persistChallenge();
        LocalDate date = LocalDate.of(2026, 6, 2);
        dayRepository.saveAndFlush(ChallengeDay.of(ch, date, 8000, DayStatus.SUCCESS));

        assertThatThrownBy(() ->
                dayRepository.saveAndFlush(ChallengeDay.of(ch, date, 9000, DayStatus.SUCCESS)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
