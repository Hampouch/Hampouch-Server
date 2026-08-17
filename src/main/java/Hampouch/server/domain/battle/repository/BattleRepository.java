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
     * 시작·종료 배치(정원 충족→start()/미달→cancel(), 결과 확정→terminate()) 공용 — Battle row를
     * PESSIMISTIC_WRITE로 잠근다. 배포 직후 캐치업과 자정 cron이 근접하거나(또는 다중 인스턴스
     * 배포에서 노드별 스케줄러가) 같은 배틀을 겨냥해 중복 실행되는 경우를 막는 게 핵심(#139 리뷰) —
     * 락 없이 읽으면 두 트랜잭션이 똑같이 이전 상태를 보고 통과해버려, 나중에 커밋하는 쪽이 앞선
     * 결과를 조용히 덮어쓸 수 있다. join()도 findByBattleCodeForUpdate로 같은 row를 잠그므로
     * 시작 배치와 참가 요청 사이의 경쟁도 이 락 위에서 함께 직렬화된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Battle b WHERE b.id = :id")
    Optional<Battle> findByIdForUpdate(@Param("id") Long id);

    /** 시작일 배치 대상 — READY 상태이고 시작일이 오늘(judgmentDate) 이하인 배틀. */
    List<Battle> findByStatusAndStartDateLessThanEqual(BattleStatus status, LocalDate judgmentDate);

    /** 종료일 배치 대상 — ONGOING 상태이고 종료일이 오늘(judgmentDate) 이전인 배틀. */
    List<Battle> findByStatusAndEndDateLessThan(BattleStatus status, LocalDate judgmentDate);
}
