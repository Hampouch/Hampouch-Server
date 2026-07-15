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
        return challengeRepository.save(
                Challenge.create(1L, 7, LocalDate.of(2026, 6, 1), 70000, 10000, false, null));
    }

    @Test
    @DisplayName("메서드 이름 파생 쿼리들이 의도한 행을 찾는다 — 유저+상태 exists·find, 챌린지별 find(단일 날짜·기간 between)")
    void derivedQueries() {
        Challenge ch = persistChallenge();

        assertThat(challengeRepository.existsByUserIdAndStatus(1L, ChallengeStatus.IN_PROGRESS)).isTrue();
        assertThat(challengeRepository.findByUserIdAndStatus(1L, ChallengeStatus.IN_PROGRESS)).isPresent();

        dayRepository.save(ChallengeDay.of(ch, LocalDate.of(2026, 6, 1), 8000, DayStatus.SUCCESS));
        dayRepository.save(ChallengeDay.of(ch, LocalDate.of(2026, 6, 3), 12000, DayStatus.OVER));

        assertThat(dayRepository.findByChallenge_IdAndDayDate(ch.getId(), LocalDate.of(2026, 6, 1))).isPresent();
        assertThat(dayRepository.findByChallenge_Id(ch.getId())).hasSize(2);
        assertThat(dayRepository.findByChallenge_IdAndDayDateBetween(
                ch.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2))).hasSize(1);
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
