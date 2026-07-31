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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * battleCode 존재 여부 조회와 uq_battle_code 유니크 제약을 H2에 실제 적용해 검증 —
 * BattleCodeGenerator의 충돌 재시도 판단이 여기 의존한다.
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
}
