package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 유저당 진행 중 챌린지 1개 제약을 실 MySQL에서 확인한다 — 같은 검증이 ChallengeRepositoryTest에 H2로도 있지만,
 * 이 제약은 DB가 status를 보고 채우는 계산 컬럼 active_user_id 위에 서 있고 H2는 그 정의를 흉내만 낸다.
 * 동시 생성 경쟁의 마지막 방어선이라 운영 엔진에서 실제로 서는지를 따로 못 박아 둔다.
 */
@MySqlContainerTest
@Transactional
class ChallengeRepositoryMySqlTest {

    @Autowired
    ChallengeRepository challengeRepository;

    /** userId의 챌린지를 저장하고, result가 있으면 그 상태로 종료시킨다(null이면 IN_PROGRESS 유지). */
    private Challenge persist(Long userId, LocalDate start, ChallengeStatus result) {
        Challenge challenge = Challenge.builder()
                .userId(userId).durationDays(7).startDate(start)
                .budgetTotal(70000).dailyLimit(10000).build();
        if (result != null) {
            challenge.applyResult(result);
        }
        return challengeRepository.save(challenge);
    }

    @Test
    @DisplayName("같은 유저의 진행 중 챌린지가 이미 있으면 두 번째 저장을 MySQL 유니크 제약이 거절한다")
    void rejectsSecondInProgressForSameUser() {
        persist(1L, LocalDate.of(2026, 6, 1), null);

        assertThatThrownBy(() -> persist(1L, LocalDate.of(2026, 6, 8), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("진행 중 챌린지를 종료 상태로 바꾸면 MySQL이 계산 컬럼을 비워 같은 유저의 새 챌린지를 받아들인다")
    void freesSlotAfterFinish() {
        Challenge first = persist(2L, LocalDate.of(2026, 6, 1), null);
        first.applyResult(ChallengeStatus.SUCCESS);
        // 상태 변경 UPDATE를 먼저 내보낸다 — id가 IDENTITY라 아래 INSERT는 즉시 나가고, UPDATE가 그보다 늦으면
        // DB엔 아직 진행 중 행이 남아 있어 제약에 걸린다
        challengeRepository.flush();

        persist(2L, LocalDate.of(2026, 6, 8), null);

        assertThat(challengeRepository.existsInProgress(2L)).isTrue();
    }
}
