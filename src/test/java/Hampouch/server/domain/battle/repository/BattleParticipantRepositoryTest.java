package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ALREADY_JOINED 판단(existsByBattle_IdAndUser_Id)·정원 카운트(countByBattle_Id)·
 * 내 참가 목록 조회(findMyParticipations)를 H2에 실제 적용해 검증 (ExpenseRepositoryTest와 동일 스타일).
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class BattleParticipantRepositoryTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    BattleRepository battleRepository;
    @Autowired
    BattleParticipantRepository battleParticipantRepository;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.createLocalUser("me@hampouch.com", "encoded", "me"));
        other = userRepository.save(User.createLocalUser("other@hampouch.com", "encoded", "other"));
    }

    private Battle battle(String code, LocalDate startDate) {
        return battleRepository.save(Battle.of(code, "짠테크 배틀", 4, 7, startDate, "치킨 사주기", other));
    }

    @Test
    @DisplayName("existsByBattle_IdAndUser_Id는 그 배틀에 그 유저가 참가한 행이 있을 때만 true다")
    void existsByBattleAndUser_reflectsParticipation() {
        Battle battle = battle("ABCD1234", LocalDate.of(2026, 8, 1));
        battleParticipantRepository.save(BattleParticipant.of(me, battle));

        assertThat(battleParticipantRepository.existsByBattle_IdAndUser_Id(battle.getId(), me.getId())).isTrue();
        assertThat(battleParticipantRepository.existsByBattle_IdAndUser_Id(battle.getId(), other.getId())).isFalse();
    }

    @Test
    @DisplayName("countByBattle_Id는 그 배틀의 참가자 수만 센다 — 다른 배틀 참가자는 섞이지 않는다")
    void countByBattle_countsOnlyThatBattle() {
        Battle battle = battle("ABCD1234", LocalDate.of(2026, 8, 1));
        Battle otherBattle = battle("ZZZZ9999", LocalDate.of(2026, 8, 1));
        battleParticipantRepository.save(BattleParticipant.of(me, battle));
        battleParticipantRepository.save(BattleParticipant.of(other, battle));
        battleParticipantRepository.save(BattleParticipant.of(me, otherBattle));

        assertThat(battleParticipantRepository.countByBattle_Id(battle.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 배틀에 같은 유저가 두 번 참가하면 유니크 제약(uq_battle_participant)이 거절한다")
    void uniqueConstraint_rejectsDuplicateJoin() {
        Battle battle = battle("ABCD1234", LocalDate.of(2026, 8, 1));
        battleParticipantRepository.saveAndFlush(BattleParticipant.of(me, battle));

        assertThatThrownBy(() -> battleParticipantRepository.saveAndFlush(BattleParticipant.of(me, battle)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findMyParticipations는 status 필터가 없으면 내 참가 전체를 startDate DESC, battleId DESC로 준다")
    void findMyParticipations_returnsAllOrderedWhenNoFilter() {
        Battle older = battle("AAAA0001", LocalDate.of(2026, 8, 1));
        Battle newer = battle("AAAA0002", LocalDate.of(2026, 9, 1));
        battleParticipantRepository.save(BattleParticipant.of(me, older));
        battleParticipantRepository.save(BattleParticipant.of(me, newer));
        battleParticipantRepository.save(BattleParticipant.of(other, older)); // 남의 참가 — 제외돼야 함

        List<BattleParticipant> result = battleParticipantRepository.findMyParticipations(me.getId(), null);

        assertThat(result).extracting(p -> p.getBattle().getId())
                .containsExactly(newer.getId(), older.getId()); // startDate가 늦은(9/1) 쪽이 먼저
    }

    @Test
    @DisplayName("findMyParticipations는 status를 지정하면 그 상태의 배틀 참가만 걸러낸다")
    void findMyParticipations_filtersByStatus() {
        Battle ready = battle("AAAA0001", LocalDate.of(2026, 8, 1));
        Battle ongoing = battle("AAAA0002", LocalDate.of(2026, 8, 2));
        ongoing.start();
        battleRepository.saveAndFlush(ongoing);
        battleParticipantRepository.save(BattleParticipant.of(me, ready));
        battleParticipantRepository.save(BattleParticipant.of(me, ongoing));

        List<BattleParticipant> result = battleParticipantRepository.findMyParticipations(me.getId(), BattleStatus.READY);

        assertThat(result).extracting(p -> p.getBattle().getId()).containsExactly(ready.getId());
    }
}
