package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.*;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantSpending;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 배틀 생성/목록/상세/참가 링크 조회/참가의 서비스 계층. 무효화·종료 배치는 이후 구현 예정.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BattleService {

    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(3, 7, 14, 31);
    private static final String DELETED_USER_NICKNAME = "탈퇴한 사용자";

    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ExpenseRepository expenseRepository;
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

    /**
     * GET /battles/{battleId}. 참가자 전원을 User와 함께 한 번에 가져온 뒤, 그 리스트로 요청자 참가 여부 판단
     * 별도 exists 쿼리를 안 태우는 이유: 참가자가 최대 10명이라 리스트 순회 비용이 쿼리 하나보다 싸기 때문.
     */
    public BattleDetailResponse getBattleDetail(Long userId, Long battleId) {
        Battle battle = battleRepository.findById(battleId)
                .orElseThrow(() -> new CustomException(BattleErrorCode.BATTLE_NOT_FOUND));

        List<BattleParticipant> participants = battleParticipantRepository.findByBattle_IdWithUser(battleId);
        boolean isParticipant = participants.stream()
                .anyMatch(p -> p.getUser().getId().equals(userId));
        if (!isParticipant) {
            throw new CustomException(BattleErrorCode.FORBIDDEN_NOT_PARTICIPANT);
        }

        List<BattleDetailResponse.ParticipantRanking> rankings = toRankings(battle, participants);
        String penaltyTargetNickname = findPenaltyTargetNickname(battle, participants, rankings);
        return BattleDetailResponse.from(battle, rankings, penaltyTargetNickname);
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
     * status별 카드 shape 분기. ONGOING/TERMINATED는 getBattleDetail()과 같은 집계 헬퍼
     * (computeOngoingSpends/toRanking)를 재사용 — 이 배틀이 내가 참가 중인 배틀 목록에서
     * 나온 것이므로 별도 FORBIDDEN 체크는 필요 없다
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
                    toOngoingParticipantAmounts(battle)
            );
            case TERMINATED -> new BattleSummary.Terminated(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    findWinnerNickname(battle)
            );
            // 도달 불가 — getMyBattles()의 필터 거절과 findMyParticipations()의 WHERE 제외로
            // 이미 두 겹 막혀 있다. sealed switch의 완전성 때문에 남기는 방어선이라, 여기 닿았다면
            // 위 두 곳 중 하나가 깨진 것이므로 조용히 넘기지 않고 터뜨린다.
            case CANCELLED -> throw new IllegalStateException(
                    "CANCELLED 배틀이 목록 조회에 도달함 — 필터/쿼리 제외 로직 확인 필요");
        };
    }

    /**
     * GET /battles/{battleId} 랭킹 리스트 — 상태별로 소스가 다르다.
     * READY/CANCELLED: 아무도 지출을 집계할 시점이 아니므로 전부 0/null.
     * ONGOING: 실시간 집계 + RankAssigner로 매 조회마다 다시 계산
     * TERMINATED: 종료 배치가 BattleParticipant.finalizeResult()로 이미 박아둔 rank/totalAmount
     * 스냅샷을 그대로 읽는다 — 햄배틀은 일괄 입력 기능이 없고 배틀 기간이 끝나면 산정되는 방식
     */
    private List<BattleDetailResponse.ParticipantRanking> toRankings(Battle battle, List<BattleParticipant> participants) {
        return switch (battle.getStatus()) {
            case READY, CANCELLED -> participants.stream()
                    .map(p -> toRanking(p, null, 0, 0))
                    .toList();
            case ONGOING -> RankAssigner.assign(computeOngoingSpends(battle, participants), ParticipantSpend::totalAmount)
                    .stream()
                    .map(ranked -> toRanking(ranked.item().participant(), ranked.rank(),
                            ranked.item().todayAmount(), ranked.item().totalAmount()))
                    .toList();
            case TERMINATED -> participants.stream()
                    // todayAmount=0 고정: 종료된 배틀엔 오늘 지출 X
                    .map(p -> toRanking(p, p.getRank(), 0, p.getTotalAmount()))
                    .toList();
        };
    }

    /** toSummary()의 ONGOING 카드용 — rank 없이 today/total만 필요해서 정렬만 하고 등수는 안 매긴다. */
    private List<BattleSummary.Ongoing.ParticipantAmount> toOngoingParticipantAmounts(Battle battle) {
        List<BattleParticipant> participants = battleParticipantRepository.findByBattle_IdWithUser(battle.getId());
        return computeOngoingSpends(battle, participants).stream()
                .sorted(Comparator.comparingInt(ParticipantSpend::totalAmount))
                .map(spend -> {
                    User user = spend.participant().getUser();
                    String avatarUrl = user.isDeleted() ? null : user.getProfileImageUrl();
                    return new BattleSummary.Ongoing.ParticipantAmount(
                            user.getId(), maskedNickname(user), avatarUrl, spend.todayAmount(), spend.totalAmount());
                })
                .toList();
    }

    /**
     * toSummary()의 TERMINATED 카드용 — rank=1 스냅샷을 가진 참가자의 닉네임. 동점 공동 1위인
     * 경우 DTO가 String 하나만 받을 수 있어 참가 순서)상 먼저인 쪽을 대표로 노출
     * (공동 우승 UI 표현은 이 DTO 범위 밖 — 확정된 디자인 근거 없음).
     * rank=1인 참가자가 없으면 종료 배치가 finalizeResult()를 아직 못 채운 데이터 정합성 문제라
     * 조용히 null을 주지 않고 터뜨린다.
     */
    private String findWinnerNickname(Battle battle) {
        List<BattleParticipant> participants = battleParticipantRepository.findByBattle_IdWithUser(battle.getId());
        BattleParticipant winner = participants.stream()
                .filter(p -> p.getRank() != null && p.getRank() == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATED 배틀에 rank=1 참가자가 없음 — 종료 배치의 finalizeResult() 반영 여부 확인 필요"));
        return maskedNickname(winner.getUser());
    }

    /**
     * 참가자 전원의 today/total 지출을 실시간 집계 — battleId를 안 가진 Expense를 유저 + 기간
     * 교집합으로 묶는 유일한 지점(sumTodayAndTotalByUsers).
     * 상한선을 battle.getEndDate()가 아닌 오늘로 잡는 이유: 미래 날짜엔 애초에 Expense row가 존재할 수 없어 결과는 같지만,
     * 지금까지의 누적이라는 의도를 쿼리 파라미터로도 명확히 하기 위함.
     */
    private List<ParticipantSpend> computeOngoingSpends(Battle battle, List<BattleParticipant> participants) {
        LocalDate today = LocalDate.now(clock);
        List<Long> userIds = participants.stream().map(p -> p.getUser().getId()).toList();

        Map<Long, BattleParticipantSpending> spendingByUser = expenseRepository
                .sumTodayAndTotalByUsers(userIds, battle.getStartDate(), today, today, ExpenseStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(BattleParticipantSpending::userId, s -> s));

        return participants.stream()
                .map(p -> {
                    BattleParticipantSpending spending = spendingByUser.get(p.getUser().getId());
                    int todayAmount = spending == null ? 0 : (int) spending.todayAmount();
                    int totalAmount = spending == null ? 0 : (int) spending.totalAmount();
                    return new ParticipantSpend(p, todayAmount, totalAmount);
                })
                .toList();
    }

    /**
     * GET /battles/{battleId}용 벌칙 대상자
     * ONGOING: 방금 계산한 rankings에서 등수가 가장 낮은 참가자를 그때그때 조회
     * TERMINATED: Battle.penaltyUser 스냅샷이 이미 있으므로 재계산하지 않고 그 유저를 participants에서 찾아 적용
     * READY/CANCELLED는 대상이 없어 null.
     */
    private String findPenaltyTargetNickname(Battle battle, List<BattleParticipant> participants,
                                              List<BattleDetailResponse.ParticipantRanking> rankings) {
        return switch (battle.getStatus()) {
            case READY, CANCELLED -> null;
            case ONGOING -> rankings.stream()
                    .max(Comparator.comparingInt(BattleDetailResponse.ParticipantRanking::rank))
                    .map(BattleDetailResponse.ParticipantRanking::nickname)
                    .orElse(null);
            case TERMINATED -> {
                User penaltyUser = battle.getPenaltyUser();
                if (penaltyUser == null) {
                    throw new IllegalStateException(
                            "TERMINATED 배틀에 penaltyUser가 없음 — 종료 배치의 terminate() 호출 여부 확인 필요");
                }
                yield participants.stream()
                        .filter(p -> p.getUser().getId().equals(penaltyUser.getId()))
                        .map(p -> maskedNickname(p.getUser()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "TERMINATED 배틀의 penaltyUser가 참가자 목록에 없음 — 데이터 정합성 확인 필요"));
            }
        };
    }

    /**
     * 탈퇴 유저 마스킹 — 닉네임은 고정 문구로 가리고 avatarUrl은 null(프론트가 기본 아바타로
     * 대체한다고 가정, 확정된 디자인 근거는 아직 없음, 임시 결정).
     * isValid는 BattleParticipant를 그대로 받아서 노출(무효화 배치 ④ 구현 전이라 지금은 항상 true).
     */
    private BattleDetailResponse.ParticipantRanking toRanking(BattleParticipant participant, Integer rank, int todayAmount, int totalAmount) {
        User user = participant.getUser();
        String avatarUrl = user.isDeleted() ? null : user.getProfileImageUrl();
        return new BattleDetailResponse.ParticipantRanking(
                user.getId(), maskedNickname(user), avatarUrl, rank, todayAmount, totalAmount, participant.isValid());
    }

    private String maskedNickname(User user) {
        return user.isDeleted() ? DELETED_USER_NICKNAME : user.getNickname();
    }

    /** computeOngoingSpends()의 결과 형태 — BattleParticipant와 today/total 집계값을 함께 들고 다닌다. */
    private record ParticipantSpend(BattleParticipant participant, int todayAmount, int totalAmount) {
    }
}
