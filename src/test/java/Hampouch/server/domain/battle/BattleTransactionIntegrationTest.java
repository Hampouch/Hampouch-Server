package Hampouch.server.domain.battle;

import Hampouch.server.domain.battle.dto.BattleDetailResponse;
import Hampouch.server.domain.battle.dto.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.CreateBattleResponse;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.battle.service.BattleService;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.BattleErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 test DB에 실제로 커밋되는지 검증 — 테스트 레벨 @Transactional을 일부러 안 붙인다(롤백되면
 * "커밋됐다"는 걸 증명 못 함, ExpenseTransactionIntegrationTest와 동일 원칙).
 * Mockito 목으로는 실행된 적 없는 실제 JPQL(findByBattle_IdWithUser의 JOIN FETCH,
 * sumTodayAndTotalByUsers의 CASE WHEN GROUP BY 집계)이 H2(MODE=MySQL)에서 진짜로 동작하는지
 * 확인하는 게 이 테스트의 핵심 목적 — 네트워크 문제로 로컬 컴파일 확인을 못 했던 부분이라 더 중요.
 */
@SpringBootTest
class BattleTransactionIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    BattleService battleService;
    @Autowired
    BattleRepository battleRepository;
    @Autowired
    BattleParticipantRepository battleParticipantRepository;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("배틀 생성은 Battle과 생성자 참가자 행을 커밋하고, 참가는 새 참가자 행을 커밋한다 " +
            "— 서비스 호출이 끝날 때마다 별도 리포지토리 조회로 다시 읽어도 반영돼 있어야 한다")
    void createAndJoinCommitRowsAfterServiceCall() {
        LocalDate today = LocalDate.now(SEOUL);
        User creator = userRepository.save(User.createLocalUser(
                "battle-tx-creator@hampouch.test", "encoded", "배틀생성자"));
        User joiner = userRepository.save(User.createLocalUser(
                "battle-tx-joiner@hampouch.test", "encoded", "배틀참가자"));

        CreateBattleResponse created = battleService.create(creator.getId(),
                new CreateBattleRequest("트랜잭션 생성 배틀", 3, 7, today, "치킨 사주기"));

        Battle persistedBattle = battleRepository.findByBattleCode(created.battleCode()).orElseThrow();
        assertThat(persistedBattle.getId()).isEqualTo(created.battleId());
        assertThat(battleParticipantRepository.existsByBattle_IdAndUser_Id(created.battleId(), creator.getId()))
                .isTrue();
        assertThat(battleParticipantRepository.countByBattle_Id(created.battleId())).isEqualTo(1);

        battleService.join(joiner.getId(), created.battleCode());

        assertThat(battleParticipantRepository.existsByBattle_IdAndUser_Id(created.battleId(), joiner.getId()))
                .isTrue();
        assertThat(battleParticipantRepository.countByBattle_Id(created.battleId())).isEqualTo(2);

        // 커밋된 참가 행을 별도 서비스 호출(재조회)이 그대로 봐야 함 — ALREADY_JOINED는
        // 서비스 계층 existsBy 체크가 방금 커밋된 행을 실제로 읽어야만 나올 수 있는 에러
        assertThatThrownBy(() -> battleService.join(joiner.getId(), created.battleCode()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.ALREADY_JOINED);
    }

    @Test
    @DisplayName("ONGOING 상세 조회는 실제 Expense 행을 CASE WHEN 집계 쿼리로 today/total 반영해서 " +
            "경쟁 순위를 매기고, 지출 없는 참가자는 0원으로 랭킹 1위가 된다")
    void getBattleDetail_ongoing_aggregatesRealExpensesAndRanks() {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate battleStart = today.minusDays(2);

        User owner = userRepository.save(User.createLocalUser(
                "battle-tx-owner@hampouch.test", "encoded", "배틀오너"));
        User bigSpender = userRepository.save(User.createLocalUser(
                "battle-tx-spender@hampouch.test", "encoded", "많이쓴사람"));
        User noSpend = userRepository.save(User.createLocalUser(
                "battle-tx-nospend@hampouch.test", "encoded", "안쓴사람"));

        Battle battle = Battle.of("TXBT0001", "트랜잭션 집계 배틀", 3, 7, battleStart, "커피 사기", owner);
        battle.start();
        battle = battleRepository.save(battle);

        battleParticipantRepository.save(BattleParticipant.of(owner, battle));
        battleParticipantRepository.save(BattleParticipant.of(bigSpender, battle));
        battleParticipantRepository.save(BattleParticipant.of(noSpend, battle));

        // owner: 과거 2000원 + 오늘 1000원 = 총 3000원(today=1000)
        expenseRepository.save(Expense.of("어제 커피", 2000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                battleStart.plusDays(1), owner));
        expenseRepository.save(Expense.of("오늘 커피", 1000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                today, owner));
        // bigSpender: 과거 8000원(오늘 지출 없음) = 총 8000원(today=0)
        expenseRepository.save(Expense.of("과거 지출", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                battleStart.plusDays(1), bigSpender));
        // noSpend: 지출 행 자체가 없음 → 0원으로 채워져야 함

        BattleDetailResponse res = battleService.getBattleDetail(owner.getId(), battle.getId());

        BattleDetailResponse.ParticipantRanking ownerRanking = participant(res, owner.getId());
        BattleDetailResponse.ParticipantRanking spenderRanking = participant(res, bigSpender.getId());
        BattleDetailResponse.ParticipantRanking noSpendRanking = participant(res, noSpend.getId());

        assertThat(ownerRanking.todayAmount()).isEqualTo(1000);
        assertThat(ownerRanking.totalAmount()).isEqualTo(3000);
        assertThat(spenderRanking.todayAmount()).isZero();
        assertThat(spenderRanking.totalAmount()).isEqualTo(8000);
        assertThat(noSpendRanking.todayAmount()).isZero();
        assertThat(noSpendRanking.totalAmount()).isZero();

        // 오름차순 랭킹: 안 쓴 사람(0원) 1위 → 오너(3000원) 2위 → 많이 쓴 사람(8000원) 3위(벌칙 대상)
        assertThat(noSpendRanking.rank()).isEqualTo(1);
        assertThat(ownerRanking.rank()).isEqualTo(2);
        assertThat(spenderRanking.rank()).isEqualTo(3);
        assertThat(res.penaltyTargetNickname()).isEqualTo(bigSpender.getNickname());
        assertThat(res.participants()).allSatisfy(p -> assertThat(p.isValid()).isTrue());
    }

    @Test
    @DisplayName("TERMINATED 상세 조회는 재집계하지 않고 참가자 스냅샷을 그대로 읽는다 " +
            "— 종료 후 추가된 Expense가 있어도 totalAmount가 스냅샷 값에서 안 바뀌어야 한다")
    void getBattleDetail_terminated_readsSnapshotNotLiveExpenses() {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate battleStart = today.minusDays(10);

        User winner = userRepository.save(User.createLocalUser(
                "battle-tx-winner@hampouch.test", "encoded", "우승자"));
        User loser = userRepository.save(User.createLocalUser(
                "battle-tx-loser@hampouch.test", "encoded", "꼴찌"));

        Battle battle = Battle.of("TXBT0002", "트랜잭션 종료 배틀", 2, 14, battleStart, "삼겹살 쏘기", winner);
        battle.start();
        battle = battleRepository.save(battle);

        BattleParticipant winnerParticipant = battleParticipantRepository.save(BattleParticipant.of(winner, battle));
        BattleParticipant loserParticipant = battleParticipantRepository.save(BattleParticipant.of(loser, battle));
        // save()가 반환한 엔티티는 그 save() 호출의 트랜잭션이 끝나며 detach된다 — finalizeResult()로
        // 메모리 값만 바꾸고 다시 save()하지 않으면 DB엔 반영이 안 된다(커밋 검증이 핵심인 테스트라 특히 중요).
        winnerParticipant.finalizeResult(1, 5000);
        loserParticipant.finalizeResult(2, 90000);
        battleParticipantRepository.save(winnerParticipant);
        battleParticipantRepository.save(loserParticipant);
        battle.terminate(loser);
        battleRepository.save(battle);

        // 종료 이후에 생긴 지출 — 재집계된다면 winner의 totalAmount가 크게 튀어야 정상
        expenseRepository.save(Expense.of("종료 후 지출", 99999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                today, winner));

        BattleDetailResponse res = battleService.getBattleDetail(winner.getId(), battle.getId());

        BattleDetailResponse.ParticipantRanking winnerRanking = participant(res, winner.getId());
        BattleDetailResponse.ParticipantRanking loserRanking = participant(res, loser.getId());

        assertThat(winnerRanking.totalAmount()).isEqualTo(5000); // 스냅샷 값 그대로 — 99999 안 섞임
        assertThat(winnerRanking.todayAmount()).isZero();
        assertThat(loserRanking.totalAmount()).isEqualTo(90000);
        assertThat(loserRanking.todayAmount()).isZero();
        assertThat(winnerRanking.rank()).isEqualTo(1);
        assertThat(loserRanking.rank()).isEqualTo(2);
        assertThat(res.penaltyTargetNickname()).isEqualTo(loser.getNickname());
    }

    private BattleDetailResponse.ParticipantRanking participant(BattleDetailResponse res, Long userId) {
        return res.participants().stream()
                .filter(p -> p.userId().equals(userId))
                .findFirst()
                .orElseThrow();
    }
}
