package Hampouch.server.domain.battle;

import Hampouch.server.domain.battle.dto.response.JoinBattleResponse;
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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * sameUserConcurrentJoinConvergesToAlreadyJoined()는 findByBattleCodeForUpdate가 두 요청을
     * 완전히 직렬화해버려서, 두 번째 요청은 항상 첫 요청이 커밋된 뒤 validateJoinable의
     * existsByBattle_IdAndUser_Id()(BattleService.java:183-184)에서 ALREADY_JOINED로 끝난다 -
     * uq_battle_participant에 걸려 DataIntegrityViolationException을 잡는 BattleService.java:124-126
     * 경로는 그 테스트로는 절대 실행되지 않는다. 그 제약이 실제로 DB에 존재하는지는 동시성 없이도
     * 검증 가능하다: 같은 (battle, user) 조합을 두 번 저장 시도해 두 번째가 막히는지 직접 확인한다.
     * 이 제약을 지우면(마이그레이션 롤백 등) 이 테스트가 실패한다.
     */
    @Test
    @DisplayName("uq_battle_participant 제약이 동일 배틀·동일 유저의 중복 참가 저장을 막고, " +
            "실제 MySQL이 돌려주는 제약 이름도 PARTICIPANT_UNIQUE와 일치한다 — " +
            "BattleService.alreadyJoinedOr()의 이름 비교가 실제 DB에서도 통하는지 확인")
        void uniqueConstraintRejectsDuplicateParticipantRow() {
        User creator = newUser("unique-constraint-creator");
        Battle battle = newBattle(creator, 4);
        User challenger = newUser("unique-constraint-challenger");
        battleParticipantRepository.save(BattleParticipant.of(challenger, battle));

        assertThatThrownBy(() ->
                battleParticipantRepository.saveAndFlush(BattleParticipant.of(challenger, battle)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
                .satisfies(name -> assertThat((String) name).containsIgnoringCase(BattleParticipant.PARTICIPANT_UNIQUE));
    }

    /**
     * battleId 행을 raw JDBC로 FOR UPDATE 잠근 채 두 콜을 제출한다. 제출 직후 바로 완료 여부를
     * 재는 대신, 두 워커가 CyclicBarrier로 battleService.join() 호출 직전 지점에 모두 도달했음을
     * 먼저 확인한 뒤에야 동시에 출발시킨다 — 스레드 스케줄링 지연으로 한쪽이 다른 쪽보다 훨씬
     * 먼저(또는 늦게) 호출을 시작해, "아직 안 끝났다"는 관찰이 실제 락 대기가 아니라 단순히
     * 스레드가 아직 안 떴다는 것만 증명하는 상황을 배제하기 위함(#207 리뷰 지적과 동일 문제).
     * 그렇게 동시에 출발한 두 호출이 여전히 완료되지 않았음을 확인해야만, 그 대기가 홀더가 쥔
     * PESSIMISTIC_WRITE 락 때문이라고 말할 수 있다. 홀더가 풀리는 순간부터 MySQL이 두 트랜잭션을
     * 내부 잠금 큐 순서대로 직렬화하므로, join() 안의 findByBattleCodeForUpdate가 실제로 두 번째
     * 요청을 첫 번째 요청의 커밋까지 대기시킨다는 것을 검증하는 셈이다.
     */
    private <T> List<Outcome<T>> race(Long battleId, Callable<? extends T> first, Callable<? extends T> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            try (PreparedStatement ps = holderConn.prepareStatement(
                    "SELECT * FROM battle WHERE battle_id = ? FOR UPDATE")) {
                ps.setLong(1, battleId);
                ps.executeQuery();
            }

            Future<Outcome<T>> firstFuture = executor.submit(() -> runAfterBarrier(barrier, first));
            Future<Outcome<T>> secondFuture = executor.submit(() -> runAfterBarrier(barrier, second));

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

    /** barrier.await()는 두 워커 모두 도착해야만 반환되므로, 이후 request.call()은 항상 동시에 출발한다. */
    private <T> Outcome<T> runAfterBarrier(CyclicBarrier barrier, Callable<? extends T> request) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Outcome<>(null, e);
        }
        return capture(request);
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

    /**
     * capacity만 받고 생성자를 첫 참가자로 등록 — join()이 채워야 할 자리 수는 capacity - 1.
     * startDate는 항상 내일로 잡는다 — 실제 시스템 Clock을 쓰는 real BattleService를 호출하므로,
     * 과거/오늘 날짜를 쓰면 validateJoinable()의 시작일 컷오프(#139)에 막혀 매 join()이
     * BATTLE_ALREADY_STARTED로 실패한다(이 테스트가 검증하려는 건 정원/중복 경쟁이지 컷오프가 아님).
     */
    private Battle newBattle(User creator, int capacity) {
        Battle battle = battleRepository.save(Battle.of(
                battleCode(), "짠테크 배틀", capacity, 7, LocalDate.now().plusDays(1), "치킨 사주기", creator));
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
