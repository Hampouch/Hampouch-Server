package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.Battle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BattleRepository extends JpaRepository<Battle, Long> {

    boolean existsByBattleCode(String battleCode);
}
