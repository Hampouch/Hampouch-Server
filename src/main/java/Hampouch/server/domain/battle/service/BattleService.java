package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.*;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.BattleErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 배틀 생성/목록/참가 링크 조회/참가의 서비스 계층. 상세조회·랭킹·배치는 이후 구현 예정.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BattleService {

    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(3, 7, 14, 31);

    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final UserRepository userRepository;
    private final BattleCodeGenerator battleCodeGenerator;
    private final Clock clock;

    /**
     * POST /battles. userRepository.getReferenceById()로 실제 SELECT 없이 프록시만 받는다 —
     * userId는 @LoginUserId(JWT 인증)를 통과한 값이라 재검증 필요 X
     */
    @Transactional
    public CreateBattleResponse create(Long userId, CreateBattleRequest request) {
        validateCapacity(request.capacity());
        validateDuration(request.durationDays());
        validateStartDate(request.startDate());

        User creator = userRepository.getReferenceById(userId);
        String battleCode = battleCodeGenerator.generate();
        Battle battle = Battle.of(battleCode, request.title(), request.capacity(), request.durationDays(),
                request.startDate(), request.penalty(), creator);
        battleRepository.save(battle);

        // 생성자 자동 첫 참가자 등록 — 참가자 명단의 유일한 출처는 BattleParticipant
        battleParticipantRepository.save(BattleParticipant.of(creator, battle));

        return CreateBattleResponse.from(battle);
    }

    /**
     * GET /battles. 내가 참가 중인 배틀이 BattleParticipant를 거쳐야만 나오는 정보라
     * BattleParticipantRepository에서 조회를 시작
     */
    public BattleListResponse getMyBattles(Long userId, BattleStatus statusFilter) {
        // 취소된 배틀은 목록에 노출하지 않기로 확정(2026-08-02)했으므로 CANCELLED는 이 API가
        // 받을 수 없는 값이다. 조용히 빈 배열을 주면 프론트의 잘못된 요청이 그대로 묻히므로 400으로 거절.
        if (statusFilter == BattleStatus.CANCELLED) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "취소된 햄배틀은 조회할 수 없습니다.");
        }

        List<BattleParticipant> participation = battleParticipantRepository.findMyParticipations(userId, statusFilter);

        List<BattleSummary> summaries = participation.stream()
                .map(p -> toSummary(p.getBattle()))
                .toList();

        return new BattleListResponse(summaries);
    }

    /**
     * GET /battles/invitations/{battleCode}. 락 없는 단순 조회 — 참가와 달리 여기선 참가자를
     * insert하지 않으므로 카운트 레이스가 응답 하나 잘못 보여주는 것 이상의 피해를 못 준다.
     * joinedCount는 validateJoinable()이 BATTLE_FULL 검증에 쓴 값을 그대로 반환받아 재사용
     */
    public BattleInvitationResponse getInvitation(Long userId, String battleCode) {
        Battle battle = battleRepository.findByBattleCode(battleCode)
                .orElseThrow(() -> new CustomException(BattleErrorCode.BATTLE_CODE_NOT_FOUND));
        int joinedCount = validateJoinable(battle, userId);
        return BattleInvitationResponse.from(battle, joinedCount);
    }

    /**
     * POST /battles/invitations/{battleCode}. findByBattleCodeForUpdate로 Battle row를 잠근 채
     * validateJoinable을 통과해야 insert까지 간다.
     * DataIntegrityViolationException은 검증 통과 직후 같은 유저의 동시 재요청이 uq_battle_participant에
     * 걸린 극단적 타이밍 케이스 방어용
     */
    @Transactional
    public JoinBattleResponse join(Long userId, String battleCode) {
        Battle battle = battleRepository.findByBattleCodeForUpdate(battleCode)
                .orElseThrow(() -> new CustomException(BattleErrorCode.BATTLE_CODE_NOT_FOUND));
        validateJoinable(battle, userId);

        User user = userRepository.getReferenceById(userId);
        try {
            battleParticipantRepository.save(BattleParticipant.of(user, battle));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(BattleErrorCode.ALREADY_JOINED);
        }
        return JoinBattleResponse.from(battle);
    }

    private void validateCapacity(int capacity) {
        if (capacity < 2 || capacity > 10) {
            throw new CustomException(BattleErrorCode.INVALID_CAPACITY_RANGE);
        }
    }

    private void validateDuration(int durationDays) {
        if (!ALLOWED_DURATIONS.contains(durationDays)) {
            throw new CustomException(BattleErrorCode.INVALID_DURATION_DAYS);
        }
    }

    private void validateStartDate(LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now(clock))) {
            throw new CustomException(BattleErrorCode.INVALID_START_DATE);
        }
    }

    /**
     * 참가 링크 조회/참가 공통 검증. CANCELLED된 Battle의 경우 400 Error를 반환하므로 다른 status보다 먼저 검증.
     * 이후 햄배틀의 상태가 READY(참여 가능)인지 조회 후, ALREADY_JOINED와 BATTLE_FULL을 검증(인원이 찬 경우에도
     * 이미 참여한 배틀의 경우 ALREADY_JOINED이므로).
     * BATTLE_FULL 검증에 쓴 joinedCount를 반환값으로 노출 — getInvitation()에 재사용해 COUNT 쿼리 중복 호출 방지.
     * CANCELLED/ALREADY_STARTED/ALREADY_JOINED로 먼저 걸리는 경우엔 그 전까지처럼 COUNT 쿼리 자체가 나가지 않는다(lazy 유지).
     */
    private int validateJoinable(Battle battle, Long userId) {
        if (battle.getStatus() == BattleStatus.CANCELLED) {
            throw new CustomException(BattleErrorCode.BATTLE_CANCELLED);
        }
        if (battle.getStatus() != BattleStatus.READY) {
            throw new CustomException(BattleErrorCode.BATTLE_ALREADY_STARTED);
        }
        if (battleParticipantRepository.existsByBattle_IdAndUser_Id(battle.getId(), userId)) {
            throw new CustomException(BattleErrorCode.ALREADY_JOINED);
        }
        int joinedCount = battleParticipantRepository.countByBattle_Id(battle.getId());
        if (joinedCount >= battle.getCapacity()) {
            throw new CustomException(BattleErrorCode.BATTLE_FULL);
        }
        return joinedCount;
    }

    /**
     * status별 카드 shape 분기. ONGOING의 todayAmount/totalAmount, TERMINATED의 winnerNickname은
     * 실시간 집계/랭킹 쿼리가 아직 없어 빈 값으로 스텁 — 시작일·종료일 배치가 없는 지금은
     * 어떤 배틀도 실제로 이 상태에 도달하지 않아 당장 잘못된 값이 나갈 일은 없음.
     */
    private BattleSummary toSummary(Battle battle) {
        return switch (battle.getStatus()) {
            case READY -> new BattleSummary.Ready(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    battle.getCapacity(), battleParticipantRepository.countByBattle_Id(battle.getId())
            );
            case ONGOING -> new BattleSummary.Ongoing(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    List.of() // TODO(③): 참가자별 today/total 실시간 집계로 교체
            );
            case TERMINATED -> new BattleSummary.Terminated(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    null // TODO(③): rank=1 참가자 닉네임으로 교체
            );
            // 도달 불가 — getMyBattles()의 필터 거절과 findMyParticipations()의 WHERE 제외로
            // 이미 두 겹 막혀 있다. sealed switch의 완전성 때문에 남기는 방어선이라, 여기 닿았다면
            // 위 두 곳 중 하나가 깨진 것이므로 조용히 넘기지 않고 터뜨린다.
            case CANCELLED -> throw new IllegalStateException(
                    "CANCELLED 배틀이 목록 조회에 도달함 — 필터/쿼리 제외 로직 확인 필요");
        };
    }
}
