package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.UserStatus;
import Hampouch.server.domain.user.service.UserOperationLock;
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
 * 배틀 자동 취소·무효화·종료 스냅샷 배치의 실제 처리 로직.
 * 대상 id 목록을 가볍게 조회하는 메서드와, 상세 조회+판정+갱신을 짧은 트랜잭션 안에서 끝내는 메서드로 분리.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BattleBatchService {

    //3·7일 배틀은 3일 미기록 무효화 규칙 예외
    private static final Set<Integer> MISSING_INPUT_EXEMPT_DURATIONS = Set.of(3, 7);
    private static final int INVALIDATION_MISSING_DAYS = 3;

    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ExpenseRepository expenseRepository;
    private final UserOperationLock userOperationLock;

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
     * 배틀 하나의 시작일 판정 — findByIdForUpdate로 Battle row를 잠근 채 정원을 재확인
     */
    @Transactional
    public void processStart(Long battleId) {
        Battle battle = battleRepository.findByIdForUpdate(battleId).orElse(null);
        if (battle == null || battle.getStatus() != BattleStatus.READY) {
            return;
        }
        int joinedCount = battleParticipantRepository.countByBattle_IdAndUser_StatusNot(battleId, UserStatus.DELETED);
        if (joinedCount >= battle.getCapacity()) {
            battle.start();
        } else {
            battle.cancel();
        }
    }

    /** 무효화 배치 대상 참가자 id 목록 — ONGOING 배틀의 아직 유효한 참가자 전원. */
    public List<Long> findInvalidationTargetIds() {
        return battleParticipantRepository.findInvalidationCandidateIds();
    }

    /**
     * 참가자 하나의 무효화 판정
     * - 탈퇴 유저는 배틀 기간·3·7일 예외와 무관하게 즉시 무효화
     * - 3일 연속 미기록이면 무효화
     */
    @Transactional
    public void processInvalidation(Long participantId, LocalDate judgmentDate) {
        BattleParticipant participant = battleParticipantRepository.findByIdWithBattle(participantId).orElse(null);
        if (participant == null || !participant.isValid()
                || participant.getBattle().getStatus() != BattleStatus.ONGOING) {
            return;
        }
        userOperationLock.lock(participant.getUser().getId());

        if (participant.getUser().isDeleted()) {
            participant.invalidate();
            return;
        }
        if (MISSING_INPUT_EXEMPT_DURATIONS.contains(participant.getBattle().getDurationDays())) {
            return;
        }
        LocalDate baseline = baselineDate(participant);
        LocalDate lastFullyElapsedDate = judgmentDate.minusDays(1);
        if (ChronoUnit.DAYS.between(baseline, lastFullyElapsedDate) >= INVALIDATION_MISSING_DAYS) {
            participant.invalidate();
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

    //배틀 하나의 종료 처리
    @Transactional
    public void processTermination(Long battleId, LocalDate judgmentDate) {
        Battle battle = battleRepository.findByIdForUpdate(battleId).orElse(null);
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
