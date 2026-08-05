package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.Battle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BattleRepository extends JpaRepository<Battle, Long> {

    boolean existsByBattleCode(String battleCode);

    /** 참가 링크 조회(GET /invitations/{battleCode})용 — 락 없는 단순 조회. 없으면 BATTLE_CODE_NOT_FOUND. */
    Optional<Battle> findByBattleCode(String battleCode);

    /**
     * 참가(POST /invitations/{battleCode}) 전용 — Battle row를 PESSIMISTIC_WRITE로 잠근다.
     * 중복 참가는 BattleParticipant의 uq_battle_participant 유니크 제약이 이미 막아주지만,
     * 정원 초과 여부를 방지를 위해 락이 필요: 4/5명 상태에서 서로 다른 두 유저가 동시에 countByBattle_Id()를 읽으면
     * 둘 다 자리 있음으로 통과해 6명이 될 수 있기 때문이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Battle b WHERE b.battleCode = :battleCode")
    Optional<Battle> findByBattleCodeForUpdate(@Param("battleCode") String battleCode);
}
