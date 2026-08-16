package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 배틀 자동 취소·무효화·종료 스냅샷 배치의 실제 처리 로직(#139). 대상 조회와 개별 처리를 분리해,
 * 호출부(BattleBatchScheduler)가 대상 하나씩을 자체 트랜잭션으로 처리하며 한 건 실패가 나머지를
 * 막지 않도록 한다 — ChallengeFinalizationScheduler/ChallengeService.finalizeDueChallenge와 동일 원칙.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BattleBatchService {

    /** #139 이슈 확정 — 3·7일 배틀은 3일 미기록 무효화 규칙 예외(기간이 짧아 판정 자체가 무의미).
     * 탈퇴 유저 즉시 무효화(아래 processInvalidation)는 이 예외와 무관하게 전 배틀에 적용된다. */
    private static final Set<Integer> MISSING_INPUT_EXEMPT_DURATIONS = Set.of(3, 7);
    private static final int INVALIDATION_MISSING_DAYS = 3;

    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ExpenseRepository expenseRepository;

    /** 시작일 배치 대상 배틀 id 목록 — READY이고 시작일이 judgmentDate 이하. */
    public List<Long> findStartTargetIds(LocalDate judgmentDate) {
        return battleRepository.findByStatusAndStartDateLessThanEqual(BattleStatus.READY, judgmentDate).stream()
                .map(Battle::getId)
                .toList();
    }

    /** 종료일 배치 대상 배틀 id 목록 — ONGOING이고 종료일이 judgmentDate 미만. */
    public List<Long> findTerminationTargetIds(LocalDate judgmentDate) {
        return battleRepository.findByStatusAndEndDateLessThan(BattleStatus.ONGOING, judgmentDate).stream()
                .map(Battle::getId)
                .toList();
    }

    /**
     * 배틀 하나의 시작일 판정 — findByIdForUpdate로 Battle row를 잠근 채 정원을 재확인한다.
     * join()도 findByBattleCodeForUpdate로 같은 row를 잠그므로 두 트랜잭션이 이 락 위에서 직렬화돼,
     * 정원 확인 시점과 실제 참가 사이 경쟁이 원천적으로 발생하지 않는다.
     * 조회 시점과 처리 시점 사이에 이미 다른 경로로 상태가 바뀌었을 수 있어(예: 배치 재실행) READY가
     * 아니면 조용히 스킵한다.
     */
    @Transactional
    public void processStart(Long battleId) {
        Battle battle = battleRepository.findByIdForUpdate(battleId).orElse(null);
        if (battle == null || battle.getStatus() != BattleStatus.READY) {
            return;
        }
        int joinedCount = battleParticipantRepository.countByBattle_Id(battleId);
        if (joinedCount >= battle.getCapacity()) {
            battle.start();
        } else {
            battle.cancel();
        }
    }

    /**
     * 무효화 판정 — ONGOING 배틀의 유효 참가자 전원을 한 트랜잭션에서 처리하며, 두 조건을 독립적으로 본다.
     * (1) 탈퇴 유저는 배틀 기간·3·7일 예외와 무관하게 즉시 무효화 — 탈퇴한 이상 더 이상 지출을 기록할
     * 수 없고 벌칙/우승도 의미가 없어서 3일 미기록을 기다릴 이유가 없다.
     * (2) 그 외엔 기존 규칙대로 3·7일 배틀을 제외한 나머지에서 3일 연속 미기록이면 무효화 — 기준일은
     * max(battle.startDate, user.lastUpdated)(lastUpdated가 null이거나 startDate 이전이면 startDate).
     * 참가자별 isValid 플립은 서로 독립적인 단일 필드 갱신이라(정원처럼 여러 참가자가 하나의 카운트를
     * 다투는 구조가 아님) 시작/종료 배치와 달리 개별 락이나 개별 트랜잭션이 필요 없다.
     */
    @Transactional
    public void processInvalidation(LocalDate judgmentDate) {
        List<BattleParticipant> candidates = battleParticipantRepository.findInvalidationCandidates();
        for (BattleParticipant participant : candidates) {
            if (participant.getUser().isDeleted()) {
                participant.invalidate();
                continue;
            }
            if (MISSING_INPUT_EXEMPT_DURATIONS.contains(participant.getBattle().getDurationDays())) {
                continue;
            }
            LocalDate baseline = baselineDate(participant);
            if (ChronoUnit.DAYS.between(baseline, judgmentDate) >= INVALIDATION_MISSING_DAYS) {
                participant.invalidate();
            }
        }
    }

    private LocalDate baselineDate(BattleParticipant participant) {
        LocalDate startDate = participant.getBattle().getStartDate();
        LocalDate lastUpdated = participant.getUser().getLastUpdated();
        if (lastUpdated == null || lastUpdated.isBefore(startDate)) {
            return startDate;
        }
        return lastUpdated;
    }

    /**
     * 배틀 하나의 종료 처리 — 참가자별 배틀 기간 총지출을 집계해 유효 참가자끼리만 RankAssigner로
     * 순위를 매기고 최하위 참가자를 벌칙 대상으로 Battle.terminate()에 전달한다. 유효 참가자가 하나도
     * 없으면(전원 무효화) 벌칙 대상 없이 terminate(null)로 종료한다.
     * 무효 참가자는 finalizeResult()를 아예 호출하지 않는다(rank/totalAmount 둘 다 계속 null) — 결과
     * 화면은 isValid만으로 "탈락" 뱃지를 그리고 금액을 표시하지 않으므로(디자인 확정) 굳이 지출을
     * 집계해서 스냅샷에 채워둘 필요가 없다.
     * ExpenseRepository.sumTodayAndTotalByBattleIds는 today 파라미터가 endDate보다 미래여도
     * endDate로 자동 clamp되므로, 종료된 배틀의 총지출 집계에 별도 쿼리 없이 그대로 재사용한다.
     */
    @Transactional
    public void processTermination(Long battleId, LocalDate judgmentDate) {
        Battle battle = battleRepository.findById(battleId).orElse(null);
        if (battle == null || battle.getStatus() != BattleStatus.ONGOING) {
            return;
        }
        List<BattleParticipant> participants = battleParticipantRepository.findByBattle_IdWithUser(battleId);
        List<BattleParticipant> validParticipants = participants.stream().filter(BattleParticipant::isValid).toList();

        if (validParticipants.isEmpty()) {
            battle.terminate(null);
            return;
        }

        Map<Long, BattleParticipantBattleSpending> spendingByUser = expenseRepository
                .sumTodayAndTotalByBattleIds(List.of(battleId), judgmentDate, ExpenseStatus.ACTIVE).stream()
                .collect(Collectors.toMap(BattleParticipantBattleSpending::userId, s -> s));

        List<RankAssigner.Ranked<BattleParticipant>> ranked =
                RankAssigner.assign(validParticipants, p -> totalAmountOf(p, spendingByUser));
        ranked.forEach(r -> r.item().finalizeResult(r.rank(), totalAmountOf(r.item(), spendingByUser)));

        BattleParticipant penaltyParticipant = ranked.stream()
                .max(Comparator.comparingInt(RankAssigner.Ranked::rank))
                .map(RankAssigner.Ranked::item)
                .orElseThrow(); // validParticipants가 비어있지 않으므로 도달 불가
        battle.terminate(penaltyParticipant.getUser());
    }

    private int totalAmountOf(BattleParticipant participant, Map<Long, BattleParticipantBattleSpending> spendingByUser) {
        BattleParticipantBattleSpending spending = spendingByUser.get(participant.getUser().getId());
        // total_amount 컬럼 자체가 INT라 여기서도 int로 저장 — DB 스키마 한도가 원천적인 상한.
        return spending == null ? 0 : (int) spending.totalAmount();
    }
}
