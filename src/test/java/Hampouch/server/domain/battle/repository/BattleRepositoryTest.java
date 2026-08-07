package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * battleCode 존재 여부 조회, battleCode 단건 조회(락 유무 두 버전), uq_battle_code 유니크 제약을
 * H2에 실제 적용해 검증 — BattleCodeGenerator의 충돌 재시도 판단과 참가 플로우가 여기 의존한다.
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class BattleRepositoryTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    BattleRepository battleRepository;

    private User creator;

    @BeforeEach
    void setUp() {
        creator = userRepository.save(User.createLocalUser("creator@hampouch.com", "encoded", "creator"));
    }

    @Test
    @DisplayName("existsByBattleCode는 저장된 코드는 true, 그 외는 false를 반환한다")
    void existsByBattleCode_reflectsSavedCode() {
        battleRepository.save(Battle.of("ABCD1234", "짠테크 배틀", 4, 7,
                LocalDate.of(2026, 8, 1), "치킨 사주기", creator));

        assertThat(battleRepository.existsByBattleCode("ABCD1234")).isTrue();
        assertThat(battleRepository.existsByBattleCode("ZZZZ9999")).isFalse();
    }

    @Test
    @DisplayName("battle_code 유니크 제약이 걸려 있어 같은 코드로 두 번째 배틀을 저장하면 데이터베이스가 거절한다")
    void battleCode_uniqueConstraintRejectsDuplicate() {
        battleRepository.saveAndFlush(Battle.of("ABCD1234", "짠테크 배틀", 4, 7,
                LocalDate.of(2026, 8, 1), "치킨 사주기", creator));

        assertThatThrownBy(() -> battleRepository.saveAndFlush(Battle.of("ABCD1234", "다른 배틀", 3, 3,
                LocalDate.of(2026, 8, 2), "커피 사주기", creator)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByBattleCode는 저장된 코드가 있으면 그 배틀을 반환한다")
    void findByBattleCode_returnsBattleWhenExists() {
        Battle saved = battleRepository.save(Battle.of("ABCD1234", "짠테크 배틀", 4, 7,
                LocalDate.of(2026, 8, 1), "치킨 사주기", creator));

        Optional<Battle> found = battleRepository.findByBattleCode("ABCD1234");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByBattleCode는 존재하지 않는 코드면 빈 값을 반환한다")
    void findByBattleCode_returnsEmptyWhenNotExists() {
        assertThat(battleRepository.findByBattleCode("ZZZZ9999")).isEmpty();
    }

    @Test
    @DisplayName("findByBattleCodeForUpdate도 findByBattleCode와 동일한 배틀을 반환한다 — " +
            "PESSIMISTIC_WRITE 자체(동시 요청 직렬화 효과)는 단일 스레드 단위 테스트로 검증할 수 없어, " +
            "여기선 락을 걸어도 조회 결과 자체는 달라지지 않는다는 것만 확인한다")
    void findByBattleCodeForUpdate_returnsSameBattleAsPlainFind() {
        Battle saved = battleRepository.save(Battle.of("ABCD1234", "짠테크 배틀", 4, 7,
                LocalDate.of(2026, 8, 1), "치킨 사주기", creator));

        Optional<Battle> found = battleRepository.findByBattleCodeForUpdate("ABCD1234");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }
}
