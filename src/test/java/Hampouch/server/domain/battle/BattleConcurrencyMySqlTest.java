package Hampouch.server.domain.battle;

import Hampouch.server.domain.battle.dto.JoinBattleResponse;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.battle.service.BattleService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.BaseErrorCode;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.BattleErrorCode;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BattleService.join의 findByBattleCodeForUpdate와 uq_battle_participant가 동시 요청에서 정원 초과·중복 참가를 막는지 검증
 */
@MySqlContainerTest
class BattleConcurrencyMySqlTest {

    @Autowired
    BattleService battleService;
    @Autowired
    BattleRepository battleRepository;
    @Autowired
    BattleParticipantRepository battleParticipantRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("마지막 한 자리에 서로 다른 두 사용자가 동시에 참가하면 하나만 성공하고 정원을 넘지 않는다")
    void lastSlotConcurrentJoinAllowsOnlyOneWinner() throws Exception {
        User creator = newUser("last-slot-creator");
        Battle battle = newBattle(creator, 2);
        User challenger = newUser("last-slot-challenger");
        User contender = newUser("last-slot-contender");

        List<Outcome<JoinBattleResponse>> outcomes = race(battle.getId(),
                () -> battleService.join(challenger.getId(), battle.getBattleCode()),
                () -> battleService.join(contender.getId(), battle.getBattleCode()));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("정확히 한 요청만 성공해야 한다").isEqualTo(1);
        assertConflict(singleFailure(outcomes), BattleErrorCode.BATTLE_FULL);
        assertThat(battleParticipantRepository.countByBattle_Id(battle.getId()))
                .as("최종 참가자 수가 capacity를 넘으면 안 된다").isEqualTo(2);
    }

    @Test
    @DisplayName("동일 사용자의 참가 요청 두 개가 동시에 오면 하나만 성공하고 다른 하나는 ALREADY_JOINED이며 500이 없다")
    void sameUserConcurrentJoinConvergesToAlreadyJoined() throws Exception {
        User creator = newUser("dup-join-creator");
        Battle battle = newBattle(creator, 4);
        User challenger = newUser("dup-join-challenger");

        List<Outcome<JoinBattleResponse>> outcomes = race(battle.getId(),
                () -> battleService.join(challenger.getId(), battle.getBattleCode()),
                () -> battleService.join(challenger.getId(), battle.getBattleCode()));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("정확히 한 요청만 성공해야 한다").isEqualTo(1);
        assertConflict(singleFailure(outcomes), BattleErrorCode.ALREADY_JOINED);
        assertThat(battleParticipantRepository.existsByBattle_IdAndUser_Id(battle.getId(), challenger.getId()))
                .isTrue();
        assertThat(battleParticipantRepository.findByBattle_IdWithUser(battle.getId()).stream()
                .filter(p -> p.getUser().getId().equals(challenger.getId())))
                .as("참가자 행은 1건만 남아야 한다").hasSize(1);
    }

    /**
     * battleId 행을 raw JDBC로 FOR UPDATE 잠근 채 두 콜을 동시에 제출해, 둘 다 그 잠금에 진짜로
     * 막히는지 확인한 뒤 풀어준다 — 홀더가 풀리는 순간부터 MySQL이 두 트랜잭션을 내부 잠금
     * 큐 순서대로 직렬화하므로, join() 안의 findByBattleCodeForUpdate가 실제로 두 번째 요청을
     * 첫 번째 요청의 커밋까지 대기시킨다는 것을 검증하는 셈이다.
     */
    private <T> List<Outcome<T>> race(Long battleId, Callable<? extends T> first, Callable<? extends T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            try (PreparedStatement ps = holderConn.prepareStatement(
                    "SELECT * FROM battle WHERE battle_id = ? FOR UPDATE")) {
                ps.setLong(1, battleId);
                ps.executeQuery();
            }

            Future<Outcome<T>> firstFuture = executor.submit(() -> capture(first));
            Future<Outcome<T>> secondFuture = executor.submit(() -> capture(second));

            Thread.sleep(500);
            assertThat(firstFuture.isDone())
                    .as("첫 번째 요청은 raw JDBC 홀더 락에 막혀 아직 완료되면 안 된다").isFalse();
            assertThat(secondFuture.isDone())
                    .as("두 번째 요청도 raw JDBC 홀더 락에 막혀 아직 완료되면 안 된다").isFalse();

            holderConn.rollback();

            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> Outcome<T> capture(Callable<? extends T> request) {
        try {
            return new Outcome<>(request.call(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private Outcome<?> singleFailure(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow();
    }

    private void assertConflict(Outcome<?> outcome, BaseErrorCode expected) {
        assertThat(outcome.error()).isInstanceOfSatisfying(CustomException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(expected);
            assertThat(error.getErrorCode().getHttpStatus().value()).isEqualTo(409);
        });
    }

    private User newUser(String scenario) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.createLocalUser(
                scenario + "-" + suffix + "@hampouch.test", "encoded", "동시성" + suffix));
    }

    /** capacity만 받고 생성자를 첫 참가자로 등록 — join()이 채워야 할 자리 수는 capacity - 1. */
    private Battle newBattle(User creator, int capacity) {
        Battle battle = battleRepository.save(Battle.of(
                battleCode(), "짠테크 배틀", capacity, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", creator));
        battleParticipantRepository.save(BattleParticipant.of(creator, battle));
        return battle;
    }

    private String battleCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
