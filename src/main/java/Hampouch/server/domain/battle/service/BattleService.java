package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.BattleListResponse;
import Hampouch.server.domain.battle.dto.BattleSummary;
import Hampouch.server.domain.battle.dto.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.CreateBattleResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 배틀 생성 + 목록 조회의 서비스 계층. 참가/랭킹/배치는 이후 구현 예정.
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
        List<BattleParticipant> participation = battleParticipantRepository.findMyParticipations(userId, statusFilter);

        List<BattleSummary> summaries = participation.stream()
                .map(p -> toSummary(p.getBattle()))
                .toList();

        return new BattleListResponse(summaries);
    }

    private void validateCapacity(int capacity) {
        if (capacity < 2 || capacity > 10) {
            throw new CustomException(BattleErrorCode.INVALID_CAPACITY_RANGE);
        }
    }

    /** durationDays는 전용 BattleErrorCode가 없어 공통 VALIDATION_ERROR + 구체 메시지로 처리 */
    private void validateDuration(int durationDays) {
        if (!ALLOWED_DURATIONS.contains(durationDays)) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "durationDays는 3/7/14/31만 가능합니다.");
        }
    }

    private void validateStartDate(LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now(clock))) {
            throw new CustomException(BattleErrorCode.INVALID_START_DATE);
        }
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
            case CANCELLED -> throw new IllegalStateException(
                    "CANCELLED 배틀의 목록 카드 shape가 아직 정의되지 않음");
        };
    }
}
