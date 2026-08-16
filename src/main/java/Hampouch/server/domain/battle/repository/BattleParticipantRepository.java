package Hampouch.server.domain.battle.repository;

import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, Long> {

    boolean existsByBattle_IdAndUser_Id(Long battleId, Long userId); // ALREADY_JOINED 판단

    int countByBattle_Id(Long battleId); // joinedCount 응답 필드 + 시작일 배치의 정원 충족 체크

    /**
     * GET /battles — 내가 참가 중인 배틀은 BattleParticipant를 통해 확인할 수 있는 정보.
     * BattleRepository가 아니라 여기서 조회를 시작한다. status는 선택 필터(null이면 전체).
     * JOIN FETCH로 Battle을 함께 가져와 N+1 방지, 정렬은 startDate와 등록 순의 DESC로 고정.
     *
     * CANCELLED를 WHERE에서 아예 빼는 이유(2026-08-02 결정): 취소된 배틀은 목록에 노출하지 않기로
     * 확정했는데, status 필터는 "null이면 전체"라 미지정 조회에 CANCELLED가 그대로 섞여 들어온다.
     * 그 상태로 BattleService.toSummary()에 닿으면 정의된 카드 shape가 없어 IllegalStateException →
     * 500이 나간다(지금은 CANCELLED로 만드는 시작일 배치가 없어서 잠재 상태). 필터 분기가 아니라
     * 쿼리 자체에서 제외해야 미지정 조회까지 한 번에 막힌다.
     */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.battle b " +
            "WHERE p.user.id = :userId " +
            "AND b.status <> Hampouch.server.domain.battle.entity.BattleStatus.CANCELLED " +
            "AND (:status IS NULL OR b.status = :status) " +
            "ORDER BY b.startDate DESC, b.id DESC")
    List<BattleParticipant> findMyParticipations(@Param("userId") Long userId, @Param("status") BattleStatus status);

    /**
     * GET /battles/{battleId} — 배틀 하나의 참가자 전원을 User와 함께 가져온다(N+1 방지).
     * findMyParticipations가 p.battle을 JOIN FETCH하는 것과 반대 방향으로 p.user를 JOIN FETCH하는
     * 이유: 여기선 battle 자체는 호출부(BattleService)가 findById로 이미 따로 들고 있고, 대신
     * 참가자마다 user.nickname/avatarUrl/status(탈퇴 마스킹 판단용)를 화면에 그대로 노출해야 한다.
     * 정렬은 참가순(joinedAt) 고정 — 랭킹 순서(등수)는 여기서 정하지 않고 서비스 계층의
     * RankAssigner가 집계된 totalAmount 기준으로 별도 정렬한다
     */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.user " +
            "WHERE p.battle.id = :battleId " +
            "ORDER BY p.joinedAt")
    List<BattleParticipant> findByBattle_IdWithUser(@Param("battleId") Long battleId);

    /**
     * GET /battles — ONGOING 카드 여러 개의 참가자를 배틀 ID 목록 기준으로 한 번에 가져온다
     * (N+1 방지, PR #128 리뷰 반영 2026-08-11). findByBattle_IdWithUser의 배치 버전 —
     * 정렬에 battle.id를 먼저 두는 이유: 호출부(BattleService)가 결과를 battle.id로
     * groupingBy할 때 같은 배틀 안에서는 참가순(joinedAt)이 그대로 유지돼야 하기 때문.
     */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.user " +
            "WHERE p.battle.id IN :battleIds " +
            "ORDER BY p.battle.id, p.joinedAt")
    List<BattleParticipant> findByBattle_IdInWithUser(@Param("battleIds") List<Long> battleIds);

    /**
     * 무효화 배치 대상 id 목록 — ONGOING 배틀의 아직 유효(isValid=true)한 참가자 전원. 일부러 id만
     * 가져오는 가벼운 조회로 둔다 — user/battle까지 JOIN FETCH해서 통째로 들고 오면 후보가 많을 때
     * 그 목록을 순회하며 갱신하는 하나의 트랜잭션이 길어지고, 그 안에서 건드린 참가자 row들이 커밋
     * 전까지 계속 잠겨있게 된다. 대신 id 목록만 가볍게 뽑고, 실제 판정·갱신은
     * BattleBatchService.processInvalidation()이 건별로 findByIdWithUserAndBattle()로 다시 조회해
     * 참가자 하나당 트랜잭션 하나로 짧게 끊어 처리한다(시작/종료 배치와 동일한 원칙).
     */
    @Query("SELECT p.id FROM BattleParticipant p JOIN p.battle b " +
            "WHERE b.status = Hampouch.server.domain.battle.entity.BattleStatus.ONGOING " +
            "AND p.isValid = true")
    List<Long> findInvalidationCandidateIds();

    /** 무효화 배치의 건별 상세 조회 — 판정에 필요한 user/battle을 이 시점에 함께 JOIN FETCH한다. */
    @Query("SELECT p FROM BattleParticipant p JOIN FETCH p.user JOIN FETCH p.battle WHERE p.id = :id")
    Optional<BattleParticipant> findByIdWithUserAndBattle(@Param("id") Long id);
}
