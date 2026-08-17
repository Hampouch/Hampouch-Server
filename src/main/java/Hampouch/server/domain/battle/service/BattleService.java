package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.request.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.response.*;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending;
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
import java.util.*;
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

    //userId는 @LoginUserId(JWT 인증)를 통과한 값이라 재검증 필요 X
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

    public BattleListResponse getMyBattles(Long userId, BattleStatus statusFilter) {
        // 취소된 배틀은 목록에 노출하지 않음
        if (statusFilter == BattleStatus.CANCELLED) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "취소된 햄배틀은 조회할 수 없습니다.");
        }

        List<BattleParticipant> participation = battleParticipantRepository.findMyParticipations(userId, statusFilter);

        // ONGOING 카드만 참가자 + 지출 집계가 필요
        List<Long> ongoingBattleIds = participation.stream()
                .map(BattleParticipant::getBattle)
                .filter(battle -> battle.getStatus() == BattleStatus.ONGOING)
                .map(Battle::getId)
                .toList();
        Map<Long, List<BattleSummary.Ongoing.ParticipantAmount>> ongoingParticipantsByBattle =
                batchOngoingParticipantAmounts(ongoingBattleIds);

        List<BattleSummary> summaries = participation.stream()
                .map(p -> toSummary(p.getBattle(), ongoingParticipantsByBattle))
                .toList();

        return new BattleListResponse(summaries);
    }

    public BattleInvitationResponse getInvitation(Long userId, String battleCode) {
        Battle battle = battleRepository.findByBattleCode(battleCode)
                .orElseThrow(() -> new CustomException(BattleErrorCode.BATTLE_CODE_NOT_FOUND));
        int joinedCount = validateJoinable(battle, userId);
        return BattleInvitationResponse.from(battle, joinedCount);
    }

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

    // 시작일은 내일 이후만 허용 - 배치 규칙과 통일
    private void validateStartDate(LocalDate startDate) {
        if (!startDate.isAfter(LocalDate.now(clock))) {
            throw new CustomException(BattleErrorCode.INVALID_START_DATE);
        }
    }

    /**
     * 참가 링크 조회/참가 공통 검증. 유효한 배틀에 대해서만 해당 API 응답을 제공하도록 하는 method.
     */
    private int validateJoinable(Battle battle, Long userId) {
        if (battle.getStatus() == BattleStatus.CANCELLED) {
            throw new CustomException(BattleErrorCode.BATTLE_CANCELLED);
        }
        if (battle.getStatus() != BattleStatus.READY || !LocalDate.now(clock).isBefore(battle.getStartDate())) {
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

    //status별 카드 shape 분기
    private BattleSummary toSummary(Battle battle, Map<Long, List<BattleSummary.Ongoing.ParticipantAmount>> ongoingParticipantsByBattle) {
        return switch (battle.getStatus()) {
            case READY -> new BattleSummary.Ready(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    battle.getCapacity(), battleParticipantRepository.countByBattle_Id(battle.getId())
            );
            case ONGOING -> new BattleSummary.Ongoing(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    ongoingParticipantsByBattle.getOrDefault(battle.getId(), List.of())
            );
            case TERMINATED -> new BattleSummary.Terminated(
                    battle.getId(), battle.getBattleCode(), battle.getTitle(), battle.getPenalty(),
                    battle.getStartDate(), battle.getEndDate(), battle.getStatus(),
                    findWinnerNickname(battle)
            );
            // 도달 불가 — getMyBattles()의 필터 거절과 findMyParticipations()의 WHERE 제외로 이중 방어
            case CANCELLED -> throw new IllegalStateException(
                    "CANCELLED 배틀이 목록 조회에 도달함 — 필터/쿼리 제외 로직 확인 필요");
        };
    }

    /**
     * GET /battles/{battleId} 랭킹 리스트
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
            case ONGOING -> rankOngoing(computeOngoingSpends(battle, participants));
            case TERMINATED -> {
                // sorted() NPE 방지(무효 및 탈퇴 시 null 처리)를 위해 검증 먼저
                participants.forEach(this::validateTerminatedSnapshot);
                yield participants.stream()
                        .sorted(Comparator.comparing(BattleParticipant::getRank, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(p -> toRanking(p, p.getRank(), 0, p.getTotalAmount() == null ? 0 : p.getTotalAmount()))
                        .toList();
            }
        };
    }

    // ONGOING 랭킹 계산 — 유효 참가자끼리만 RankAssigner로 경쟁시키고, 무효화된 참가자는 목록 맨 뒤
    private List<BattleDetailResponse.ParticipantRanking> rankOngoing(List<ParticipantSpend> spends) {
        Map<Boolean, List<ParticipantSpend>> byValidity = spends.stream()
                .collect(Collectors.partitioningBy(s -> s.participant().isValid()));

        List<BattleDetailResponse.ParticipantRanking> rankings = new ArrayList<>(
                RankAssigner.assign(byValidity.get(true), ParticipantSpend::totalAmount).stream()
                        .map(ranked -> toRanking(ranked.item().participant(), ranked.rank(),
                                ranked.item().todayAmount(), ranked.item().totalAmount()))
                        .toList());
        byValidity.get(false).forEach(s -> rankings.add(
                toRanking(s.participant(), null, s.todayAmount(), s.totalAmount())));
        return rankings;
    }

    // TERMINATED 참가자에 종료 배치가 남겨야 할 스냅샷이 있는지 검증.
    private void validateTerminatedSnapshot(BattleParticipant p) {
        if (!p.isValid()) {
            return;
        }
        if (p.getRank() == null || p.getTotalAmount() == null) {
            throw new IllegalStateException(
                    "TERMINATED 배틀의 유효 참가자에 rank/totalAmount 스냅샷이 없음(userId=" +
                            p.getUser().getId() + ") — 종료 배치의 finalizeResult() 반영 여부 확인 필요");
        }
    }

    //toSummary()의 ONGOING 카드용 — 참가 목록 전체의 ONGOING 배틀들을 배틀 ID 목록 기준으로 조회·집계한 뒤 배틀별로 결합.
    private Map<Long, List<BattleSummary.Ongoing.ParticipantAmount>> batchOngoingParticipantAmounts(List<Long> battleIds) {
        if (battleIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<BattleParticipant>> participantsByBattle = battleParticipantRepository
                .findByBattle_IdInWithUser(battleIds).stream()
                .collect(Collectors.groupingBy(p -> p.getBattle().getId()));

        LocalDate today = LocalDate.now(clock);
        Map<Long, List<BattleParticipantBattleSpending>> spendingByBattle = expenseRepository
                .sumTodayAndTotalByBattleIds(battleIds, today, ExpenseStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(BattleParticipantBattleSpending::battleId));

        Map<Long, List<BattleSummary.Ongoing.ParticipantAmount>> result = new HashMap<>();
        for (Long battleId : battleIds) {
            List<BattleParticipant> participants = participantsByBattle.getOrDefault(battleId, List.of());
            Map<Long, BattleParticipantBattleSpending> spendingByUser = spendingByBattle
                    .getOrDefault(battleId, List.of()).stream()
                    .collect(Collectors.toMap(BattleParticipantBattleSpending::userId, s -> s));

            List<BattleSummary.Ongoing.ParticipantAmount> amounts = participants.stream()
                    .map(p -> {
                        BattleParticipantBattleSpending spending = spendingByUser.get(p.getUser().getId());
                        long todayAmount = spending == null ? 0 : spending.todayAmount();
                        long totalAmount = spending == null ? 0 : spending.totalAmount();
                        User user = p.getUser();
                        String avatarUrl = user.isDeleted() ? null : user.getProfileImageUrl();
                        return new BattleSummary.Ongoing.ParticipantAmount(
                                user.getId(), maskedNickname(user), avatarUrl, todayAmount, totalAmount);
                    })
                    .sorted(Comparator.comparingLong(BattleSummary.Ongoing.ParticipantAmount::totalAmount))
                    .toList();
            result.put(battleId, amounts);
        }
        return result;
    }

    private String findWinnerNickname(Battle battle) {
        List<BattleParticipant> participants = battleParticipantRepository.findByBattle_IdWithUser(battle.getId());
        Optional<BattleParticipant> winner = participants.stream()
                .filter(p -> p.getRank() != null && p.getRank() == 1)
                .findFirst();
        if (winner.isPresent()) {
            return maskedNickname(winner.get().getUser());
        }
        if (participants.stream().noneMatch(BattleParticipant::isValid)) {
            return null;
        }
        throw new IllegalStateException(
                "TERMINATED 배틀에 rank=1 참가자가 없음 — 종료 배치의 finalizeResult() 반영 여부 확인 필요");
    }

    //참가자 전원의 today/total 지출 실시간 집계
    private List<ParticipantSpend> computeOngoingSpends(Battle battle, List<BattleParticipant> participants) {
        LocalDate today = LocalDate.now(clock);
        LocalDate aggregationEnd = today.isAfter(battle.getEndDate()) ? battle.getEndDate() : today;
        List<Long> userIds = participants.stream().map(p -> p.getUser().getId()).toList();

        Map<Long, BattleParticipantSpending> spendingByUser = expenseRepository
                .sumTodayAndTotalByUsers(userIds, battle.getStartDate(), aggregationEnd, today, ExpenseStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(BattleParticipantSpending::userId, s -> s));

        return participants.stream()
                .map(p -> {
                    BattleParticipantSpending spending = spendingByUser.get(p.getUser().getId());
                    // SUM()은 JPQL에서 Long으로 집계되는데 여기서 int로 좁히면 아주 큰 합계에서 조용히 오버플로가 날 수 있었다.
                    long todayAmount = spending == null ? 0 : spending.todayAmount();
                    long totalAmount = spending == null ? 0 : spending.totalAmount();
                    return new ParticipantSpend(p, todayAmount, totalAmount);
                })
                .toList();
    }

    /**
     * GET /battles/{battleId}용 벌칙 대상자
     * ONGOING: 방금 계산한 rankings에서 등수가 가장 낮은 참가자를 그때그때 조회
     * TERMINATED: Battle.penaltyUser 스냅샷이 있으면 그 유저를 participants에서 찾아 적용.
     */
    private String findPenaltyTargetNickname(Battle battle, List<BattleParticipant> participants,
                                              List<BattleDetailResponse.ParticipantRanking> rankings) {
        return switch (battle.getStatus()) {
            case READY, CANCELLED -> null;
            case ONGOING -> rankings.stream()
                    .filter(r -> r.rank() != null)
                    .max(Comparator.comparingInt(BattleDetailResponse.ParticipantRanking::rank))
                    .map(BattleDetailResponse.ParticipantRanking::nickname)
                    .orElse(null);
            case TERMINATED -> {
                User penaltyUser = battle.getPenaltyUser();
                if (penaltyUser != null) {
                    yield participants.stream()
                            .filter(p -> p.getUser().getId().equals(penaltyUser.getId()))
                            .map(p -> maskedNickname(p.getUser()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "TERMINATED 배틀의 penaltyUser가 참가자 목록에 없음 — 데이터 정합성 확인 필요"));
                }
                if (participants.stream().noneMatch(BattleParticipant::isValid)) {
                    yield null;
                }
                throw new IllegalStateException(
                        "TERMINATED 배틀에 penaltyUser가 없음 — 종료 배치의 terminate() 호출 여부 확인 필요");
            }
        };
    }

    /**
     * 탈퇴 유저 마스킹 — 닉네임은 고정 문구로 가리고 avatarUrl은 null(TODO: 임시 결정)
     */
    private BattleDetailResponse.ParticipantRanking toRanking(BattleParticipant participant, Integer rank, long todayAmount, long totalAmount) {
        User user = participant.getUser();
        String avatarUrl = user.isDeleted() ? null : user.getProfileImageUrl();
        return new BattleDetailResponse.ParticipantRanking(
                user.getId(), maskedNickname(user), avatarUrl, rank, todayAmount, totalAmount, participant.isValid());
    }

    private String maskedNickname(User user) {
        return user.isDeleted() ? DELETED_USER_NICKNAME : user.getNickname();
    }

    /** computeOngoingSpends()의 결과 형태 — BattleParticipant와 today/total 집계값을 함께 들고 다닌다. */
    private record ParticipantSpend(BattleParticipant participant, long todayAmount, long totalAmount) {
    }
}
