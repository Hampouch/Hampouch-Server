package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.*;
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

        // ONGOING 카드만 참가자+지출 집계가 필요하다 — 배틀마다 쿼리 2개씩 추가되던 N+1을
        // 배틀 ID 목록 기준 일괄 조회 2번으로 줄인다
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
     * 이후 햄배틀의 상태가 READY(참여 가능)인지, 그리고 시작일 당일이 아직 안 됐는지(날짜 컷오프) 확인한 뒤
     * ALREADY_JOINED와 BATTLE_FULL을 검증(인원이 찬 경우에도 이미 참여한 배틀의 경우 ALREADY_JOINED이므로).
     * 날짜 컷오프는 제품 요구사항(시작일 당일부터 참가 마감)이자, 시작일 배치와의 경쟁을 원천 차단하는
     * 효과도 겸한다 — 배치도 findByIdForUpdate로 Battle row를 잠그긴 하지만(이중 방어) 참가가 이미
     * 시작일 전에 마감되므로 배치가 count를 볼 시점엔 경쟁할 참가 요청 자체가 없다.
     * 기존 BattleTransactionIntegrationTest는 startDate=오늘인 배틀에 당일 참가하는 걸 전제로 하는데,
     * 이 컷오프와 어긋나므로 Test 단계에서 함께 고친다(그 전까진 실패 상태로 남아있는 게 정상).
     * BATTLE_FULL 검증에 쓴 joinedCount를 반환값으로 노출 — getInvitation()에 재사용해 COUNT 쿼리 중복 호출 방지.
     * CANCELLED/ALREADY_STARTED/ALREADY_JOINED로 먼저 걸리는 경우엔 그 전까지처럼 COUNT 쿼리 자체가 나가지 않는다(lazy 유지).
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

    /**
     * status별 카드 shape 분기. TERMINATED는 getBattleDetail()과 같은 집계 헬퍼(findWinnerNickname)를
     * 재사용 — 이 배틀이 내가 참가 중인 배틀 목록에서 나온 것이므로 별도 FORBIDDEN 체크는 필요 없다.
     * ONGOING은 getMyBattles()가 배틀 ID 목록 기준으로 미리 일괄 조회해둔 맵에서 꺼내 쓴다
     */
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
     * ONGOING/TERMINATED 공통으로 무효화(isValid=false)된 참가자는 등수 경쟁에서 제외한다(rank=null) —
     * 무효화 시점부터 종료 시점까지 랭킹 취급이 갑자기 바뀌지 않도록 일관되게 적용.
     */
    private List<BattleDetailResponse.ParticipantRanking> toRankings(Battle battle, List<BattleParticipant> participants) {
        return switch (battle.getStatus()) {
            case READY, CANCELLED -> participants.stream()
                    .map(p -> toRanking(p, null, 0, 0))
                    .toList();
            case ONGOING -> rankOngoing(computeOngoingSpends(battle, participants));
            case TERMINATED -> {
                // rank/totalAmount 스냅샷 검증을 정렬보다 먼저 한다 — sorted()가 예상 밖의 null rank를
                // 만나면 의미 불명확한 NPE로 죽어버려서, 검증 없이 정렬부터 하면 안 된다.
                participants.forEach(this::validateTerminatedSnapshot);
                // findByBattle_IdWithUser()는 참가순(joinedAt)으로 오므로, rank 오름차순으로 다시 정렬해야
                // 응답이 실제 순위 순서(1등부터)로 나간다 — 무효 참가자는 rank=null이라 nullsLast로 맨 뒤에
                // 배치. todayAmount=0 고정(종료된 배틀엔 오늘 지출 X), totalAmount도 무효 참가자는 종료
                // 배치가 애초에 안 채워서(디자인 확정 — 탈락 참가자는 화면에 금액을 노출하지 않음) null이라 0으로 채움
                // (DTO의 totalAmount는 primitive라 null을 못 받고, isValid=false를 보고 프론트가 값을 안 그린다).
                yield participants.stream()
                        .sorted(Comparator.comparing(BattleParticipant::getRank, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(p -> toRanking(p, p.getRank(), 0, p.getTotalAmount() == null ? 0 : p.getTotalAmount()))
                        .toList();
            }
        };
    }

    /**
     * ONGOING 랭킹 계산 — 유효 참가자끼리만 RankAssigner로 경쟁시키고, 무효화된 참가자는 rank 없이
     * (todayAmount/totalAmount는 그대로 노출한 채) 목록 맨 뒤에 붙인다. TERMINATED 스냅샷과 같은
     * 원칙(무효 참가자는 경쟁 제외, 정보는 유지)을 조회 시점마다 실시간으로 재현한다.
     */
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

    /**
     * TERMINATED 참가자에 종료 배치가 남겨야 할 스냅샷이 있는지 검증.
     * rank/totalAmount 둘 다 유효(isValid=true) 참가자에게만 필수다 — 무효 참가자는 결과 화면이 금액을
     * 노출하지 않기로 확정돼(디자인 확인) 종료 배치가 finalizeResult()를 아예 호출하지 않고 둘 다 null로
     * 남기기 때문에, 무효 참가자는 이 검증에서 완전히 제외한다.
     * 없으면 toRanking() 처리 중 의미 불명확한 NPE가 나는 대신, 원인(종료 배치의 finalizeResult() 미반영)을
     * 바로 알 수 있는 예외로 막는다.
     */
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

    /**
     * toSummary()의 ONGOING 카드용 — getBattleDetail()의 computeOngoingSpends()(단일 배틀)와 달리
     * 여기는 참가 목록 전체의 ONGOING 배틀들을 배틀 ID 목록 기준으로 한 번에 조회·집계한 뒤 배틀별로
     * 묶는다. rank 없이 today/total만 필요해서 정렬만 하고 등수는 안 매긴다.
     */
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

    /**
     * toSummary()의 TERMINATED 카드용 — rank=1 스냅샷을 가진 참가자의 닉네임. 동점 공동 1위인
     * 경우 DTO가 String 하나만 받을 수 있어 참가 순서)상 먼저인 쪽을 대표로 노출
     * (공동 우승 UI 표현은 이 DTO 범위 밖 — 확정된 디자인 근거 없음).
     * 참가자 전원이 무효화돼 유효 참가자가 하나도 없으면 승자가 없는 게 정상이라 null을 반환하고,
     * 유효 참가자가 있는데도 rank=1이 없으면 종료 배치가 finalizeResult()를 아직 못 채운 데이터
     * 정합성 문제라 조용히 넘기지 않고 터뜨린다.
     */
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

    /**
     * 참가자 전원의 today/total 지출을 실시간 집계 — battleId를 안 가진 Expense를 유저 + 기간
     * 교집합으로 묶는 유일한 지점(sumTodayAndTotalByUsers).
     * 집계 상한은 min(오늘, endDate)로 clamp — endDate가
     * 지났는데 종료 배치가 아직 안 돌아서 여전히 ONGOING인 배틀이 실제로 있을 수 있다
     * 그 창구에서 상한을 그냥 오늘로 두면 배틀 종료일 다음 날 지출까지 조용히 합계에 섞여 들어간다.
     * today parameter 도입 시, 오늘이 endDate 이후라면 clamp된 end로 인해 BETWEEN 조건 자체에서
     * 걸러지므로 todayAmount도 자연히 0이 된다 — today까지 따로 clamp할 필요는 없다.
     */
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
     * ONGOING: 방금 계산한 rankings에서 등수가 가장 낮은 참가자를 그때그때 조회 — 무효 참가자는 rank가
     * 없어 이 max() 비교에 애초에 안 들어오므로 별도 필터 없이도 유효 참가자 중 최하위가 뽑힌다.
     * TERMINATED: Battle.penaltyUser 스냅샷이 있으면 그 유저를 participants에서 찾아 적용.
     * penaltyUser가 null인데 유효 참가자가 하나도 없으면(전원 무효화) 종료 배치가 의도적으로
     * terminate(null)을 호출한 정상 상태라 null을 그대로 반환하고, 유효 참가자가 있는데도 null이면
     * 종료 배치가 terminate() 호출을 빠뜨린 데이터 정합성 문제라 예외를 던진다.
     * READY/CANCELLED는 대상이 없어 null.
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
     * 탈퇴 유저 마스킹 — 닉네임은 고정 문구로 가리고 avatarUrl은 null(프론트가 기본 아바타로
     * 대체한다고 가정, 확정된 디자인 근거는 아직 없음, 임시 결정).
     * isValid는 BattleParticipant를 그대로 받아서 노출(무효화 배치 ④ 구현 전이라 지금은 항상 true).
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
