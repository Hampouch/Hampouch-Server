package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, Long> {

    boolean existsByBattle_IdAndUser_Id(Long battleId, Long userId); // ALREADY_JOINED 판단

    int countByBattle_Id(Long battleId); // joinedCount 응답 필드 + 시작일 배치의 정원 충족 체크

    /**
     * GET /battles — 내가 참가 중인 배틀은 BattleParticipant를 통해 확인할 수 있는 정보.
     * BattleRepository가 아니라 여기서 조회를 시작한다. status는 선택 필터(null이면 전체).
     * JOIN FETCH로 Battle을 함께 가져와 N+1 방지, 정렬은 startDate와 등록 순의 DESC로 고정
     */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.battle b " +
            "WHERE p.user.id = :userId AND (:status IS NULL OR b.status = :status) " +
            "ORDER BY b.startDate DESC, b.id DESC")
    List<BattleParticipant> findMyParticipations(@Param("userId") Long userId, @Param("status") BattleStatus status);
}
