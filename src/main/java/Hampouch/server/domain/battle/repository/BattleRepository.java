package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * 시작일 배치(정원 충족→start()/미달→cancel()) 전용 — Battle row를 PESSIMISTIC_WRITE로 잠근다.
     * BattleService.validateJoinable()이 시작일 당일부터 참가를 이미 막아주긴 하지만(날짜 컷오프),
     * 배치가 count 확인과 상태 전이를 findByBattleCodeForUpdate()와 동일한 원칙으로 잠근 채 처리하도록
     * 방어선을 하나 더 둔다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Battle b WHERE b.id = :id")
    Optional<Battle> findByIdForUpdate(@Param("id") Long id);

    /** 시작일 배치 대상 — READY 상태이고 시작일이 오늘(judgmentDate) 이하인 배틀. */
    List<Battle> findByStatusAndStartDateLessThanEqual(BattleStatus status, LocalDate judgmentDate);

    /** 종료일 배치 대상 — ONGOING 상태이고 종료일이 오늘(judgmentDate) 이전인 배틀. */
    List<Battle> findByStatusAndEndDateLessThan(BattleStatus status, LocalDate judgmentDate);
}
