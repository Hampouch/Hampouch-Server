package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, Long> {

    boolean existsByBattle_IdAndUser_Id(Long battleId, Long userId); // ALREADY_JOINED 판단

    int countByBattle_Id(Long battleId); // joinedCount 응답 필드 + BATTLE_FULL 체크(join() 시점 기준 그대로 유지)

    //시작일 배치의 정원 재확인 전용 - 탈퇴 유저는 정원 제외
    int countByBattle_IdAndUser_StatusNot(Long battleId, UserStatus status);

    /** GET /battles — 내가 참가 중인 배틀은 BattleParticipant를 통해 확인할 수 있는 정보. */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.battle b " +
            "WHERE p.user.id = :userId " +
            "AND b.status <> Hampouch.server.domain.battle.entity.BattleStatus.CANCELLED " +
            "AND (:status IS NULL OR b.status = :status) " +
            "ORDER BY b.startDate DESC, b.id DESC")
    List<BattleParticipant> findMyParticipations(@Param("userId") Long userId, @Param("status") BattleStatus status);

    /** GET /battles/{battleId} — 배틀 하나의 참가자 전원을 User와 함께 가져온다 */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.user " +
            "WHERE p.battle.id = :battleId " +
            "ORDER BY p.joinedAt")
    List<BattleParticipant> findByBattle_IdWithUser(@Param("battleId") Long battleId);

    /** GET /battles — ONGOING 카드 여러 개의 참가자를 배틀 ID 목록 기준으로 한 번에 가져온다 */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.user " +
            "WHERE p.battle.id IN :battleIds " +
            "ORDER BY p.battle.id, p.joinedAt")
    List<BattleParticipant> findByBattle_IdInWithUser(@Param("battleIds") List<Long> battleIds);

    //무효화 배치 대상 id 목록 — ONGOING 배틀의 유효(isValid=true)한 참가자 전원.
    @Query("SELECT p.id FROM BattleParticipant p JOIN p.battle b " +
            "WHERE b.status = Hampouch.server.domain.battle.entity.BattleStatus.ONGOING " +
            "AND p.isValid = true")
    List<Long> findInvalidationCandidateIds();

    //무효화 배치의 건별 상세 조회
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.battle WHERE p.id = :id")
    Optional<BattleParticipant> findByIdWithBattle(@Param("id") Long id);
}
