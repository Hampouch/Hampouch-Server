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
     * 중복 참가는 BattleParticipant의 유니크 제약으로, 정원 초과 여부 방지는 이 lock을 통해 방어.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Battle b WHERE b.battleCode = :battleCode")
    Optional<Battle> findByBattleCodeForUpdate(@Param("battleCode") String battleCode);

    //시작·종료 배치 공용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Battle b WHERE b.id = :id")
    Optional<Battle> findByIdForUpdate(@Param("id") Long id);

    /** 시작일 배치 대상 — READY 상태이고 시작일이 오늘(judgmentDate) 이하인 배틀. */
    List<Battle> findByStatusAndStartDateLessThanEqual(BattleStatus status, LocalDate judgmentDate);

    /** 종료일 배치 대상 — ONGOING 상태이고 종료일이 오늘(judgmentDate) 이전인 배틀. */
    List<Battle> findByStatusAndEndDateLessThan(BattleStatus status, LocalDate judgmentDate);
}
