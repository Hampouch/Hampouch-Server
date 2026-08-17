package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BattleBatchService의 시작/무효화/종료 판정 로직 검증(#139). 리포지토리는 Mockito 목 — DB 불필요
 * (BattleServiceTest와 동일 스타일). 대상 목록 조회(findXTargetIds)는 리포지토리 위임만 하는 얇은
 * 메서드라 별도 테스트 없이 processX() 쪽만 집중 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BattleBatchServiceTest {

    private static final Long BATTLE_ID = 10L;

    @Mock
    BattleRepository battleRepository;
    @Mock
    BattleParticipantRepository battleParticipantRepository;
    @Mock
    ExpenseRepository expenseRepository;

    private BattleBatchService service() {
        return new BattleBatchService(battleRepository, battleParticipantRepository, expenseRepository);
    }

    private static User user(Long id) {
        User user = User.createLocalUser("user" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Battle battle(BattleStatus status, int capacity, int durationDays, LocalDate startDate) {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", capacity, durationDays, startDate, "치킨 사주기", user(99L));
        ReflectionTestUtils.setField(battle, "id", BATTLE_ID);
        switch (status) {
            case ONGOING -> battle.start();
            case CANCELLED -> battle.cancel();
            case READY -> { /* 기본값 */ }
            case TERMINATED -> throw new IllegalArgumentException("이 헬퍼는 TERMINATED를 안 만듦 — terminate()는 개별 테스트에서 직접 호출");
        }
        return battle;
    }

    // ---------- processStart ----------

    @Test
    @DisplayName("정원이 충족되면 배틀을 시작한다")
    void processStart_startsWhenCapacityMet() {
        Battle battle = battle(BattleStatus.READY, 4, 7, LocalDate.of(2026, 8, 10));
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.countByBattle_IdAndUser_StatusNot(BATTLE_ID, UserStatus.DELETED)).thenReturn(4);

        service().processStart(BATTLE_ID);

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.ONGOING);
    }

    @Test
    @DisplayName("정원이 미달이면 배틀을 취소한다")
    void processStart_cancelsWhenCapacityNotMet() {
        Battle battle = battle(BattleStatus.READY, 4, 7, LocalDate.of(2026, 8, 10));
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.countByBattle_IdAndUser_StatusNot(BATTLE_ID, UserStatus.DELETED)).thenReturn(2);

        service().processStart(BATTLE_ID);

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.CANCELLED);
    }

    @Test
    @DisplayName("탈퇴한 참가자는 정원 계산에서 빠진다 — 2명 정원에 활성 1명+탈퇴 1명이면 미달로 취소한다(#139 리뷰)")
    void processStart_excludesDeletedParticipantsFromCapacityCount() {
        Battle battle = battle(BattleStatus.READY, 2, 7, LocalDate.of(2026, 8, 10));
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        // countByBattle_Id로는 2(정원 충족)지만, 탈퇴 유저를 뺀 실제 활성 인원은 1명뿐 -> 미달로 취소돼야 함
        when(battleParticipantRepository.countByBattle_IdAndUser_StatusNot(BATTLE_ID, UserStatus.DELETED)).thenReturn(1);

        service().processStart(BATTLE_ID);

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.CANCELLED);
    }

    @Test
    @DisplayName("배틀을 찾을 수 없으면 조용히 건너뛴다")
    void processStart_skipsWhenBattleNotFound() {
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.empty());

        service().processStart(BATTLE_ID);

        verify(battleParticipantRepository, never()).countByBattle_IdAndUser_StatusNot(anyLong(), any());
    }

    @Test
    @DisplayName("이미 READY가 아니면(재실행 등으로 상태가 이미 바뀐 경우) 조용히 건너뛴다")
    void processStart_skipsWhenNotReady() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 7, LocalDate.of(2026, 8, 10));
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));

        service().processStart(BATTLE_ID);

        verify(battleParticipantRepository, never()).countByBattle_IdAndUser_StatusNot(anyLong(), any());
        assertThat(battle.getStatus()).isEqualTo(BattleStatus.ONGOING);
    }

    // ---------- processInvalidation ----------

    private static final Long PARTICIPANT_ID = 100L;

    private BattleParticipant participantWithId(Battle battle, User user) {
        BattleParticipant participant = BattleParticipant.of(user, battle);
        ReflectionTestUtils.setField(participant, "id", PARTICIPANT_ID);
        return participant;
    }

    @Test
    @DisplayName("탈퇴한 유저는 배틀 기간·3/7일 예외와 무관하게 즉시 무효화한다")
    void processInvalidation_invalidatesDeletedUserRegardlessOfExemption() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 3, LocalDate.of(2026, 8, 10)); // 3일 배틀(무효화 예외 대상)
        User deletedUser = user(1L);
        deletedUser.delete();
        BattleParticipant participant = participantWithId(battle, deletedUser);
        ReflectionTestUtils.setField(deletedUser, "lastUpdated", LocalDate.of(2026, 8, 12)); // 미기록 기간과 무관해야 함
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 12));

        assertThat(participant.isValid()).isFalse();
    }

    @Test
    @DisplayName("기준일로부터 정확히 3일 미기록이면 무효화한다(경계값)")
    void processInvalidation_invalidatesAtExactlyThreeMissingDays() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 14, LocalDate.of(2026, 8, 1));
        User user = user(1L);
        ReflectionTestUtils.setField(user, "lastUpdated", LocalDate.of(2026, 8, 12));
        BattleParticipant participant = participantWithId(battle, user);
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 15)); // 8/12 -> 8/15, 3일 경과

        assertThat(participant.isValid()).isFalse();
    }

    @Test
    @DisplayName("미기록 2일차까지는 무효화하지 않는다")
    void processInvalidation_doesNotInvalidateAtTwoMissingDays() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 14, LocalDate.of(2026, 8, 1));
        User user = user(1L);
        ReflectionTestUtils.setField(user, "lastUpdated", LocalDate.of(2026, 8, 13));
        BattleParticipant participant = participantWithId(battle, user);
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 15)); // 8/13 -> 8/15, 2일 경과

        assertThat(participant.isValid()).isTrue();
    }

    @Test
    @DisplayName("3·7일 배틀은 3일 미기록으로는 무효화하지 않는다(#139 확정 예외)")
    void processInvalidation_exemptsThreeAndSevenDayBattles() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 7, LocalDate.of(2026, 8, 1));
        User user = user(1L);
        ReflectionTestUtils.setField(user, "lastUpdated", LocalDate.of(2026, 8, 1)); // 훨씬 오래 미기록
        BattleParticipant participant = participantWithId(battle, user);
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 15));

        assertThat(participant.isValid()).isTrue();
    }

    @Test
    @DisplayName("lastUpdated가 없으면(첫 지출 전) 배틀 시작일을 기준일로 삼는다")
    void processInvalidation_usesStartDateWhenLastUpdatedNull() {
        Battle battle = battle(BattleStatus.ONGOING, 4, 14, LocalDate.of(2026, 8, 12));
        User user = user(1L); // lastUpdated 세팅 안 함 -> null
        BattleParticipant participant = participantWithId(battle, user);
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 15)); // startDate 8/12 -> 8/15, 3일 경과

        assertThat(participant.isValid()).isFalse();
    }

    @Test
    @DisplayName("참가자를 찾을 수 없으면 조용히 건너뛴다")
    void processInvalidation_skipsWhenParticipantNotFound() {
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.empty());

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 15));
        // 예외 없이 끝나면 통과 — 검증할 상태 자체가 없음
    }

    @Test
    @DisplayName("배틀이 ONGOING이 아니면(재실행 등) 조용히 건너뛴다")
    void processInvalidation_skipsWhenBattleNotOngoing() {
        Battle battle = battle(BattleStatus.READY, 4, 14, LocalDate.of(2026, 8, 20));
        User user = user(1L);
        BattleParticipant participant = participantWithId(battle, user);
        when(battleParticipantRepository.findByIdWithUserAndBattle(PARTICIPANT_ID)).thenReturn(Optional.of(participant));

        service().processInvalidation(PARTICIPANT_ID, LocalDate.of(2026, 8, 25));

        assertThat(participant.isValid()).isTrue();
    }

    // ---------- processTermination ----------

    @Test
    @DisplayName("유효 참가자끼리 총지출로 순위를 매기고 최하위를 벌칙 대상으로 종료한다")
    void processTermination_ranksValidParticipantsAndAssignsPenaltyToWorst() {
        Battle battle = battle(BattleStatus.ONGOING, 3, 14, LocalDate.of(2026, 8, 1));
        User best = user(1L);
        User middle = user(2L);
        User worst = user(3L);
        BattleParticipant bestP = BattleParticipant.of(best, battle);
        BattleParticipant middleP = BattleParticipant.of(middle, battle);
        BattleParticipant worstP = BattleParticipant.of(worst, battle);
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID))
                .thenReturn(List.of(bestP, middleP, worstP));
        when(expenseRepository.sumTodayAndTotalByBattleIds(List.of(BATTLE_ID), LocalDate.of(2026, 8, 20), ExpenseStatus.ACTIVE))
                .thenReturn(List.of(
                        new BattleParticipantBattleSpending(BATTLE_ID, 1L, 0, 1000),
                        new BattleParticipantBattleSpending(BATTLE_ID, 2L, 0, 5000),
                        new BattleParticipantBattleSpending(BATTLE_ID, 3L, 0, 90000)));

        service().processTermination(BATTLE_ID, LocalDate.of(2026, 8, 20));

        assertThat(bestP.getRank()).isEqualTo(1);
        assertThat(bestP.getTotalAmount()).isEqualTo(1000);
        assertThat(middleP.getRank()).isEqualTo(2);
        assertThat(worstP.getRank()).isEqualTo(3);
        assertThat(battle.getStatus()).isEqualTo(BattleStatus.TERMINATED);
        assertThat(battle.getPenaltyUser()).isEqualTo(worst);
    }

    @Test
    @DisplayName("무효화된 참가자는 순위 경쟁에서 제외되고 finalizeResult가 호출되지 않는다(rank/totalAmount 계속 null)")
    void processTermination_excludesInvalidParticipantsFromRanking() {
        Battle battle = battle(BattleStatus.ONGOING, 3, 14, LocalDate.of(2026, 8, 1));
        User valid1 = user(1L);
        User valid2 = user(2L);
        User invalidUser = user(3L);
        BattleParticipant valid1P = BattleParticipant.of(valid1, battle);
        BattleParticipant valid2P = BattleParticipant.of(valid2, battle);
        BattleParticipant invalidP = BattleParticipant.of(invalidUser, battle);
        invalidP.invalidate();
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID))
                .thenReturn(List.of(valid1P, valid2P, invalidP));
        when(expenseRepository.sumTodayAndTotalByBattleIds(List.of(BATTLE_ID), LocalDate.of(2026, 8, 20), ExpenseStatus.ACTIVE))
                .thenReturn(List.of(
                        new BattleParticipantBattleSpending(BATTLE_ID, 1L, 0, 1000),
                        new BattleParticipantBattleSpending(BATTLE_ID, 2L, 0, 5000)));

        service().processTermination(BATTLE_ID, LocalDate.of(2026, 8, 20));

        assertThat(valid1P.getRank()).isEqualTo(1);
        assertThat(valid2P.getRank()).isEqualTo(2);
        assertThat(invalidP.getRank()).isNull();
        assertThat(invalidP.getTotalAmount()).isNull();
        assertThat(battle.getPenaltyUser()).isEqualTo(valid2); // 유효 참가자 중 최하위만 벌칙 대상 후보
    }

    @Test
    @DisplayName("참가자 전원이 무효화됐으면 벌칙 대상 없이 종료하고 지출 집계 자체를 안 한다")
    void processTermination_terminatesWithoutPenaltyWhenAllInvalid() {
        Battle battle = battle(BattleStatus.ONGOING, 2, 14, LocalDate.of(2026, 8, 1));
        BattleParticipant p1 = BattleParticipant.of(user(1L), battle);
        BattleParticipant p2 = BattleParticipant.of(user(2L), battle);
        p1.invalidate();
        p2.invalidate();
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(p1, p2));

        service().processTermination(BATTLE_ID, LocalDate.of(2026, 8, 20));

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.TERMINATED);
        assertThat(battle.getPenaltyUser()).isNull();
        verify(expenseRepository, never()).sumTodayAndTotalByBattleIds(anyList(), any(), any());
    }

    @Test
    @DisplayName("배틀을 찾을 수 없으면 조용히 건너뛴다")
    void processTermination_skipsWhenBattleNotFound() {
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.empty());

        service().processTermination(BATTLE_ID, LocalDate.of(2026, 8, 20));

        verify(battleParticipantRepository, never()).findByBattle_IdWithUser(anyLong());
    }

    @Test
    @DisplayName("배틀이 ONGOING이 아니면(재실행 등) 조용히 건너뛴다")
    void processTermination_skipsWhenBattleNotOngoing() {
        Battle battle = battle(BattleStatus.CANCELLED, 2, 14, LocalDate.of(2026, 8, 1));
        when(battleRepository.findByIdForUpdate(BATTLE_ID)).thenReturn(Optional.of(battle));

        service().processTermination(BATTLE_ID, LocalDate.of(2026, 8, 20));

        verify(battleParticipantRepository, never()).findByBattle_IdWithUser(anyLong());
    }
}
