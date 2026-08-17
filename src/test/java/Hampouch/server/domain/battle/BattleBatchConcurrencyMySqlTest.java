package Hampouch.server.domain.battle;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.battle.service.BattleBatchService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배틀 배치의 락 관련 동시성 정합성을 실제 MySQL로 검증한다(#139 리뷰).
 * (1) processStart()/processTermination()이 findByIdForUpdate(PESSIMISTIC_WRITE)로 Battle row를
 * 잠그는 게, 같은 배틀을 겨냥한 중복 실행(배포 직후 캐치업과 자정 cron이 근접하거나, 다중 인스턴스
 * 배포에서 여러 노드가 같은 cron 시각에 각자 스케줄러를 돌리는 상황을 가정 — Spring @Scheduled는
 * 노드 간 리더 선출을 하지 않는다)에서도 상태 전이가 정확히 한 번만 일어나고 예외가 새지 않는지 검증.
 * join()과의 경쟁은 BattleConcurrencyMySqlTest가 이미 다루므로 여기선 배치 자체의 중복 실행
 * 안전성에 집중한다. processInvalidation()의 상태 전이 자체는 두 번 실행돼도 항상 같은 값
 * (isValid=false)으로 수렴하는 멱등 연산이라 이 관점에선 따로 다루지 않는다.
 * (2) processInvalidation()이 UserOperationLock으로 user row를 잠근 뒤에야 user 필드(특히
 * lastUpdated)를 읽는 게, 지출 저장(ExpenseService.createLocked()도 같은 락을 탐)과 겹칠 때
 * 갱신 전 스냅샷이 아니라 커밋된 최신 값을 보는지 검증.
 */
@MySqlContainerTest
class BattleBatchConcurrencyMySqlTest {

    @Autowired
    BattleBatchService battleBatchService;
    @Autowired
    BattleRepository battleRepository;
    @Autowired
    BattleParticipantRepository battleParticipantRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("정원 미달 배틀에 시작일 배치가 중복 동시 실행돼도 예외 없이 정확히 CANCELLED 한 번으로 수렴한다")
    void concurrentDuplicateProcessStart_convergesToCancelledWhenCapacityNotMet() throws Exception {
        User creator = newUser("dup-start-cancel-creator");
        Battle battle = newBattle(creator, 3); // creator만 참가 -> 1/3, 정원 미달

        List<Outcome<Void>> outcomes = race(battle.getId(),
                () -> runProcessStart(battle.getId()),
                () -> runProcessStart(battle.getId()));

        assertThat(outcomes).as("중복 실행이어도 둘 다 예외 없이 끝나야 한다(두 번째 호출은 상태 재확인 후 조용히 스킵)")
                .allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        assertThat(battleRepository.findById(battle.getId()).orElseThrow().getStatus())
                .isEqualTo(BattleStatus.CANCELLED);
    }

    @Test
    @DisplayName("정원이 정확히 찬 배틀에 시작일 배치가 중복 동시 실행돼도 예외 없이 정확히 ONGOING 한 번으로 수렴한다")
    void concurrentDuplicateProcessStart_convergesToOngoingWhenCapacityMet() throws Exception {
        User creator = newUser("dup-start-ongoing-creator");
        Battle battle = newBattle(creator, 2);
        User joiner = newUser("dup-start-ongoing-joiner");
        battleParticipantRepository.save(BattleParticipant.of(joiner, battle)); // 2/2, 정원 충족

        List<Outcome<Void>> outcomes = race(battle.getId(),
                () -> runProcessStart(battle.getId()),
                () -> runProcessStart(battle.getId()));

        assertThat(outcomes).as("중복 실행이어도 둘 다 예외 없이 끝나야 한다")
                .allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        assertThat(battleRepository.findById(battle.getId()).orElseThrow().getStatus())
                .isEqualTo(BattleStatus.ONGOING);
    }

    @Test
    @DisplayName("종료 조건(ONGOING+종료일 경과)을 만족한 배틀에 종료 배치가 중복 동시 실행돼도 " +
            "예외 없이 TERMINATED 한 번으로 수렴한다(#139 리뷰 — processTermination도 findByIdForUpdate로 잠그도록 수정)")
    void concurrentDuplicateProcessTermination_convergesToSingleTermination() throws Exception {
        User creator = newUser("dup-terminate-creator");
        User joiner = newUser("dup-terminate-joiner");
        LocalDate startDate = LocalDate.now().minusDays(5);
        Battle battle = battleRepository.save(Battle.of(
                battleCode(), "짠테크 배틀", 2, 3, startDate, "치킨 사주기", creator)); // 3일 배틀 -> endDate = startDate+2, 이미 지남
        battle.start();
        battle = battleRepository.save(battle);
        battleParticipantRepository.save(BattleParticipant.of(creator, battle));
        battleParticipantRepository.save(BattleParticipant.of(joiner, battle));
        Long battleId = battle.getId();
        LocalDate judgmentDate = LocalDate.now();

        List<Outcome<Void>> outcomes = race(battleId,
                () -> runProcessTermination(battleId, judgmentDate),
                () -> runProcessTermination(battleId, judgmentDate));

        assertThat(outcomes).as("중복 실행이어도 둘 다 예외 없이 끝나야 한다(두 번째 호출은 락 이후 재확인에서 스킵)")
                .allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        assertThat(battleRepository.findById(battleId).orElseThrow().getStatus())
                .isEqualTo(BattleStatus.TERMINATED);
    }

    @Test
    @DisplayName("무효화 판정 중 지출 저장이 lastUpdated를 갱신하면, 같은 유저 락으로 직렬화되어 " +
            "무효화 배치가 갱신 전 스냅샷이 아니라 커밋된 최신 값을 보고 판정한다(#139 리뷰)")
    void invalidationSeesLatestLastUpdatedAfterConcurrentExpenseWrite() throws Exception {
        LocalDate startDate = LocalDate.now().minusDays(20);
        LocalDate judgmentDate = LocalDate.now();
        User creator = newUser("race-lastupdated-creator");
        // 갱신이 안 되면 무효화 경계(어제까지 통째로 지나간 3일 미기록, #139 리뷰의 경계값 수정 반영)에
        // 걸리도록 미리 세팅 — judgmentDate 당일은 미기록일로 안 세므로 -4일이 정확한 경계다.
        jdbc.update("UPDATE users SET last_updated = ? WHERE user_id = ?",
                java.sql.Date.valueOf(judgmentDate.minusDays(4)), creator.getId());

        Battle battle = battleRepository.save(Battle.of(
                battleCode(), "짠테크 배틀", 4, 14, startDate, "치킨 사주기", creator));
        battle.start();
        battle = battleRepository.save(battle);
        BattleParticipant participant = battleParticipantRepository.save(BattleParticipant.of(creator, battle));
        Long participantId = participant.getId();
        Long userId = creator.getId();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);
            // "지출 저장이 user row를 먼저 잠근" 상황을 흉내낸다 — ExpenseService.createLocked()도
            // userOperationLock.lock()으로 이 row를 PESSIMISTIC_WRITE로 잠그고서야 lastUpdated를 바꾼다.
            try (PreparedStatement lockPs = holderConn.prepareStatement(
                    "SELECT * FROM users WHERE user_id = ? FOR UPDATE")) {
                lockPs.setLong(1, userId);
                lockPs.executeQuery();
            }

            Future<Outcome<Void>> invalidationFuture = executor.submit(() -> capture(
                    () -> { battleBatchService.processInvalidation(participantId, judgmentDate); return null; }));

            Thread.sleep(500);
            assertThat(invalidationFuture.isDone())
                    .as("무효화 배치는 지출 저장을 흉내낸 홀더 트랜잭션의 유저 락에 막혀 아직 완료되면 안 된다")
                    .isFalse();

            // "지출 저장"이 lastUpdated를 오늘로 갱신하고 커밋
            try (PreparedStatement updatePs = holderConn.prepareStatement(
                    "UPDATE users SET last_updated = ? WHERE user_id = ?")) {
                updatePs.setDate(1, java.sql.Date.valueOf(judgmentDate));
                updatePs.setLong(2, userId);
                updatePs.executeUpdate();
            }
            holderConn.commit();

            Outcome<Void> outcome = invalidationFuture.get(15, TimeUnit.SECONDS);
            assertThat(outcome.succeeded()).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(battleParticipantRepository.findById(participantId).orElseThrow().isValid())
                .as("무효화 배치가 락 해제 후 갱신된 최신 lastUpdated(오늘)를 봤다면 무효화되면 안 된다")
                .isTrue();
    }

    private Void runProcessStart(Long battleId) {
        battleBatchService.processStart(battleId);
        return null;
    }

    private Void runProcessTermination(Long battleId, LocalDate judgmentDate) {
        battleBatchService.processTermination(battleId, judgmentDate);
        return null;
    }

    /**
     * battleId 행을 raw JDBC로 FOR UPDATE 잠근 채 두 콜을 제출한다. 제출 직후 바로 완료 여부를
     * 재는 대신, 두 워커가 CyclicBarrier로 processStart() 호출 직전 지점에 모두 도달했음을
     * 먼저 확인한 뒤에야 동시에 출발시킨다(BattleConcurrencyMySqlTest.race()와 동일 원칙) — 그래야
     * "아직 안 끝났다"는 관찰이 실제 락 대기 때문이라고 말할 수 있다.
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
                    .as("첫 번째 호출은 raw JDBC 홀더 락에 막혀 아직 완료되면 안 된다").isFalse();
            assertThat(secondFuture.isDone())
                    .as("두 번째 호출도 raw JDBC 홀더 락에 막혀 아직 완료되면 안 된다").isFalse();

            holderConn.rollback();

            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

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

    private User newUser(String scenario) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.createLocalUser(
                scenario + "-" + suffix + "@hampouch.test", "encoded", "배치동시성" + suffix));
    }

    /** startDate를 과거로 둬도 무방하다 — 여기서 테스트하는 건 join() 마감 규칙이 아니라 배치 자체다. */
    private Battle newBattle(User creator, int capacity) {
        Battle battle = battleRepository.save(Battle.of(
                battleCode(), "짠테크 배틀", capacity, 7, LocalDate.now().minusDays(1), "치킨 사주기", creator));
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
