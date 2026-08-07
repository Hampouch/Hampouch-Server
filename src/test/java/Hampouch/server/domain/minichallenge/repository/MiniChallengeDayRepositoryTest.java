package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파생 쿼리 + 유니크 제약을 H2에 실제 적용해 검증. (@CreatedDate 위해 Clock·Auditing 설정 import — #1과 동일)
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class MiniChallengeDayRepositoryTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);

    @Autowired
    MiniChallengeRepository miniRepository;
    @Autowired
    MiniChallengeDayRepository dayRepository;

    private MiniChallenge persistMini(Long userId) {
        return miniRepository.save(MiniChallenge.create(userId, "오늘 커피 사먹지 않기", 7, START));
    }

    @Test
    @DisplayName("메서드 이름 파생 쿼리들이 의도한 행을 찾는다 — 유저별 미니 목록·그날 체크 존재 여부(exists)")
    void derivedQueries() {
        MiniChallenge mine = persistMini(1L);
        persistMini(2L); // 남의 미니 — findByUserId(1L)에 섞이면 안 됨

        assertThat(miniRepository.findByUserId(1L)).hasSize(1);
        assertThat(mine.getCreatedAt()).isNotNull(); // @CreatedDate가 Auditing으로 채워짐

        dayRepository.save(MiniChallengeDay.of(mine, START));
        assertThat(dayRepository.existsByMiniChallenge_IdAndCheckDate(mine.getId(), START)).isTrue();
        assertThat(dayRepository.existsByMiniChallenge_IdAndCheckDate(mine.getId(), START.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("체크 행을 저장하면 체크한 시각(checkedAt)이 자동으로 채워진다 — 이 값이 채워져야 '재체크해도 최초 체크 시각 보존'이 성립한다")
    void checkedAtAutoFilled() {
        MiniChallenge mine = persistMini(1L);

        MiniChallengeDay saved = dayRepository.saveAndFlush(MiniChallengeDay.of(mine, START));

        assertThat(saved.getCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 미니의 같은 날짜로 두 번 체크 행을 저장하면 DB의 유니크 제약(중복 행 금지 규칙)이 거부한다 — 하루 한 행 보장")
    void uniqueConstraintOnDuplicateCheckDate() {
        MiniChallenge mine = persistMini(1L);
        dayRepository.saveAndFlush(MiniChallengeDay.of(mine, START.plusDays(1)));

        assertThatThrownBy(() ->
                dayRepository.saveAndFlush(MiniChallengeDay.of(mine, START.plusDays(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("in 절 일괄 조회가 여러 미니의 체크 이력을 조회일 이하만 한 번에 가져온다 — 낱개로 조회하면 미니 N개에 쿼리가 목록 1 + N번 나가는 것(N+1)을 한 번으로 줄인다")
    void bulkFetchChecksUpToAsOfDate() {
        MiniChallenge m1 = persistMini(1L);
        MiniChallenge m2 = persistMini(1L);
        dayRepository.save(MiniChallengeDay.of(m1, START));               // 7/1 — 포함
        dayRepository.save(MiniChallengeDay.of(m1, START.plusDays(2)));   // 7/3 — 포함(경계: asOf 당일)
        dayRepository.save(MiniChallengeDay.of(m1, START.plusDays(4)));   // 7/5 — 제외(asOf 이후)
        dayRepository.save(MiniChallengeDay.of(m2, START.plusDays(1)));   // 7/2 — 포함

        List<MiniChallengeDay> rows = dayRepository.findByMiniChallenge_IdInAndCheckDateLessThanEqual(
                List.of(m1.getId(), m2.getId()), START.plusDays(2));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(MiniChallengeDay::getCheckDate)
                .doesNotContain(START.plusDays(4));
    }

    @Test
    @DisplayName("삭제 쿼리 둘 다 지우라는 것만 지운다 — 한 날짜를 지워도 다른 날짜 행은 남고, 이미 없는 날짜를 또 지우면 에러 없이 0건이며, 미니 하나를 통째로 지울 때는 그 미니의 체크 행이 전부 지워진다")
    void bulkDeletes() {
        MiniChallenge mine = persistMini(1L);
        dayRepository.save(MiniChallengeDay.of(mine, START));
        dayRepository.save(MiniChallengeDay.of(mine, START.plusDays(1)));

        assertThat(dayRepository.deleteByMiniChallenge_IdAndCheckDate(mine.getId(), START)).isEqualTo(1);
        assertThat(dayRepository.deleteByMiniChallenge_IdAndCheckDate(mine.getId(), START)).isZero(); // 이미 없음 → 0건(멱등)
        assertThat(dayRepository.existsByMiniChallenge_IdAndCheckDate(mine.getId(), START.plusDays(1))).isTrue();

        assertThat(dayRepository.deleteByMiniChallenge_Id(mine.getId())).isEqualTo(1); // 남은 7/2 한 건
        assertThat(dayRepository.findByMiniChallenge_IdInAndCheckDateLessThanEqual(
                List.of(mine.getId()), START.plusDays(10))).isEmpty();
    }
}
