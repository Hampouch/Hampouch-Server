package Hampouch.server.domain.expense;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseUpdateRequest;
import Hampouch.server.domain.expense.dto.NoSpendRecordRequest;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MySqlContainerTest
@Import(ExpenseNoSpendConsistencyMySqlTest.PausingUserLockConfig.class)
class ExpenseNoSpendConsistencyMySqlTest {

    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDate OLDEST_DATE = RECORD_DATE.minusDays(10);
    private static final LocalDate MIDDLE_DATE = RECORD_DATE.minusDays(5);

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ChallengeRepository challengeRepository;

    @Autowired
    PausingUserOperationLock pausingUserOperationLock;

    @Test
    @DisplayName("무지출 기록이 먼저 사용자 행을 잠그면 지출 생성은 커밋까지 기다리고 최종 상태에는 지출만 남는다")
    void createWaitsForNoSpendAndRemovesItAfterLockRelease() throws Exception {
        User user = saveUser("no-spend-first", "무지출선행");
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> noSpendFuture = executor.submit(() ->
                        expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(RECORD_DATE)));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<ExpenseCreateResponse> createFuture = executor.submit(() ->
                        expenseService.create(user.getId(), request("점심")));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(createFuture);

                pause.release();
                noSpendFuture.get(5, TimeUnit.SECONDS);
                ExpenseCreateResponse created = createFuture.get(5, TimeUnit.SECONDS);

                assertFinalExpenseOnly(user.getId(), created.expenseId());
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("지출 생성이 먼저 사용자 행을 잠그면 무지출 요청은 커밋까지 기다린 뒤 지출을 보고 추가 저장하지 않는다")
    void noSpendWaitsForCreateAndDoesNotPersistAfterLockRelease() throws Exception {
        User user = saveUser("expense-first", "지출선행");
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<ExpenseCreateResponse> createFuture = executor.submit(() ->
                        expenseService.create(user.getId(), request("저녁")));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> noSpendFuture = executor.submit(() ->
                        expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(RECORD_DATE)));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(noSpendFuture);

                pause.release();
                ExpenseCreateResponse created = createFuture.get(5, TimeUnit.SECONDS);
                noSpendFuture.get(5, TimeUnit.SECONDS);

                assertFinalExpenseOnly(user.getId(), created.expenseId());
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("삭제가 먼저 사용자 행을 잠그면 수정은 삭제 커밋까지 기다린 뒤 404로 끝나고 지출을 되살리지 않는다")
    void updateWaitsForDeleteAndDoesNotRestoreDeletedExpense() throws Exception {
        User user = saveUser("delete-first", "삭제선행");
        Expense expense = saveExpense(user, "삭제할 지출");
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), expense.getId()));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<ExpenseCreateResponse> updateFuture = executor.submit(() ->
                        expenseService.update(user.getId(), expense.getId(), updateRequest("삭제 뒤 수정")));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(updateFuture);

                pause.release();
                deleteFuture.get(5, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> updateFuture.get(5, TimeUnit.SECONDS));

                assertThat(failure.getCause())
                        .isInstanceOf(CustomException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_NOT_FOUND);
                assertThat(expenseRepository.findById(expense.getId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.DELETED);
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated()).isNull();
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("수정이 먼저 사용자 행을 잠그면 삭제는 수정 커밋까지 기다린 뒤 최신 지출을 삭제한다")
    void deleteWaitsForUpdateAndDeletesCommittedExpense() throws Exception {
        User user = saveUser("update-first", "수정선행");
        Expense expense = saveExpense(user, "수정할 지출");
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<ExpenseCreateResponse> updateFuture = executor.submit(() ->
                        expenseService.update(user.getId(), expense.getId(), updateRequest("수정 후 삭제")));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), expense.getId()));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(deleteFuture);

                pause.release();
                updateFuture.get(5, TimeUnit.SECONDS);
                deleteFuture.get(5, TimeUnit.SECONDS);

                Expense deleted = expenseRepository.findById(expense.getId()).orElseThrow();
                assertThat(deleted.getName()).isEqualTo("수정 후 삭제");
                assertThat(deleted.getStatus()).isEqualTo(ExpenseStatus.DELETED);
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated()).isNull();
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("최신 지출 삭제가 먼저 사용자 행을 잠그면, 중간 날짜 지출 생성은 삭제가 되돌린 lastUpdated 위로 다시 전진시킨다")
    void createWaitsForDeleteAndAdvancesLastUpdatedFromRecomputedFallback() throws Exception {
        User user = saveUser("delete-then-create", "삭제후생성");
        SeededExpenses seeded = seedOldestAndLatestExpenses(user);
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), seeded.latest().getId()));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<ExpenseCreateResponse> createFuture = executor.submit(() ->
                        expenseService.create(user.getId(), request("중간 지출", MIDDLE_DATE)));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(createFuture);

                pause.release();
                deleteFuture.get(5, TimeUnit.SECONDS);
                ExpenseCreateResponse created = createFuture.get(5, TimeUnit.SECONDS);

                assertThat(expenseRepository.findById(seeded.latest().getId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.DELETED);
                assertThat(expenseRepository.findById(created.expenseId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.ACTIVE);
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated())
                        .isEqualTo(MIDDLE_DATE);
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("중간 날짜 지출 생성이 먼저 사용자 행을 잠그면, 최신 지출 삭제는 그 커밋을 보고 lastUpdated를 중간 날짜로 재계산한다")
    void deleteWaitsForCreateAndRecomputesLastUpdatedIncludingNewExpense() throws Exception {
        User user = saveUser("create-then-delete", "생성후삭제");
        SeededExpenses seeded = seedOldestAndLatestExpenses(user);
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<ExpenseCreateResponse> createFuture = executor.submit(() ->
                        expenseService.create(user.getId(), request("중간 지출", MIDDLE_DATE)));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), seeded.latest().getId()));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(deleteFuture);

                pause.release();
                ExpenseCreateResponse created = createFuture.get(5, TimeUnit.SECONDS);
                deleteFuture.get(5, TimeUnit.SECONDS);

                assertThat(expenseRepository.findById(seeded.latest().getId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.DELETED);
                assertThat(expenseRepository.findById(created.expenseId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.ACTIVE);
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated())
                        .isEqualTo(MIDDLE_DATE);
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("최신 지출 삭제가 먼저 사용자 행을 잠그면, 중간 날짜 무지출 기록은 삭제가 되돌린 lastUpdated 위로 다시 전진시킨다")
    void noSpendWaitsForDeleteAndAdvancesLastUpdatedFromRecomputedFallback() throws Exception {
        User user = saveUser("delete-then-no-spend", "삭제후무지출");
        SeededExpenses seeded = seedOldestAndLatestExpenses(user);
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), seeded.latest().getId()));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> noSpendFuture = executor.submit(() ->
                        expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(MIDDLE_DATE)));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(noSpendFuture);

                pause.release();
                deleteFuture.get(5, TimeUnit.SECONDS);
                noSpendFuture.get(5, TimeUnit.SECONDS);

                assertThat(expenseRepository.findById(seeded.latest().getId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.DELETED);
                assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(user.getId(), MIDDLE_DATE)).isTrue();
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated())
                        .isEqualTo(MIDDLE_DATE);
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("중간 날짜 무지출 기록이 먼저 사용자 행을 잠그면, 최신 지출 삭제는 그 커밋을 보고 lastUpdated를 중간 날짜로 재계산한다")
    void deleteWaitsForNoSpendAndRecomputesLastUpdatedIncludingNewRecord() throws Exception {
        User user = saveUser("no-spend-then-delete", "무지출후삭제");
        SeededExpenses seeded = seedOldestAndLatestExpenses(user);
        LockPause pause = pauseFirstLock(user.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> noSpendFuture = executor.submit(() ->
                        expenseService.recordNoSpend(user.getId(), new NoSpendRecordRequest(MIDDLE_DATE)));
                assertThat(pause.firstLocked().await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> deleteFuture = executor.submit(() -> expenseService.delete(user.getId(), seeded.latest().getId()));
                assertThat(pause.twoAttempts().await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(deleteFuture);

                pause.release();
                noSpendFuture.get(5, TimeUnit.SECONDS);
                deleteFuture.get(5, TimeUnit.SECONDS);

                assertThat(expenseRepository.findById(seeded.latest().getId()).orElseThrow().getStatus())
                        .isEqualTo(ExpenseStatus.DELETED);
                assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(user.getId(), MIDDLE_DATE)).isTrue();
                assertThat(userRepository.findById(user.getId()).orElseThrow().getLastUpdated())
                        .isEqualTo(MIDDLE_DATE);
            } finally {
                pause.release();
                executor.shutdownNow();
            }
        }
    }

    /**
     * OLDEST_DATE~RECORD_DATE를 덮는 진행 중 챌린지를 같이 심는다 — #228 규칙(진행 중 챌린지가 있으면
     * 그 기간 내 지출만 변경 가능)에 이 테스트 파일의 고정 날짜들이 항상 걸리게 해, 지금이 언제든
     * (규칙 5의 과거 허용 범위와) 무관하게 동작하도록 만든다. 이 파일의 관심사는 락 순서지 날짜 범위가 아니다.
     */
    private User saveUser(String emailPrefix, String nickname) {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                emailPrefix + "@hampouch.test", "encoded", nickname));
        challengeRepository.saveAndFlush(Challenge.builder()
                .userId(user.getId()).durationDays(15).startDate(OLDEST_DATE)
                .budgetTotal(150000).dailyLimit(10000).build());
        return user;
    }

    private ExpenseCreateRequest request(String name) {
        return request(name, RECORD_DATE);
    }

    private ExpenseCreateRequest request(String name, LocalDate date) {
        return new ExpenseCreateRequest(
                name,
                8000,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                date,
                null,
                null);
    }

    private ExpenseUpdateRequest updateRequest(String name) {
        return new ExpenseUpdateRequest(
                name,
                12000,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                RECORD_DATE,
                null);
    }

    private Expense saveExpense(User user, String name) {
        user.updateLastUpdated(RECORD_DATE);
        User persistedUser = userRepository.saveAndFlush(user);
        return expenseRepository.saveAndFlush(Expense.of(
                name,
                8000,
                ExpenseCategory.CAFE,
                ExpenseEmotion.STRESS,
                RECORD_DATE,
                persistedUser));
    }

    /** OLDEST_DATE·RECORD_DATE(최신) 지출을 미리 남겨 lastUpdated=RECORD_DATE로 세팅한 픽스처. RECORD_DATE 지출을 지우면 재계산 폴백이 OLDEST_DATE로 떨어진다. */
    private SeededExpenses seedOldestAndLatestExpenses(User persistedUser) {
        Expense oldest = expenseRepository.saveAndFlush(Expense.of(
                "오래된 지출", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, OLDEST_DATE, persistedUser));
        Expense latest = expenseRepository.saveAndFlush(Expense.of(
                "최신 지출", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, RECORD_DATE, persistedUser));
        persistedUser.updateLastUpdated(RECORD_DATE);
        userRepository.saveAndFlush(persistedUser);
        return new SeededExpenses(oldest, latest);
    }

    private record SeededExpenses(Expense oldest, Expense latest) {
    }

    private LockPause pauseFirstLock(Long userId) {
        return pausingUserOperationLock.pauseFirstLock(userId);
    }

    private void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private void assertFinalExpenseOnly(Long userId, Long expenseId) {
        List<Expense> activeExpenses = expenseRepository.findByUser_IdAndExpenseDateAndStatus(
                userId, RECORD_DATE, ExpenseStatus.ACTIVE);

        assertThat(activeExpenses).hasSize(1);
        assertThat(activeExpenses.getFirst().getId()).isEqualTo(expenseId);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, RECORD_DATE)).isFalse();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated()).isEqualTo(RECORD_DATE);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PausingUserLockConfig {

        @Bean
        @Primary
        PausingUserOperationLock pausingUserOperationLock(UserRepository userRepository) {
            return new PausingUserOperationLock(userRepository);
        }
    }

    static class PausingUserOperationLock extends UserOperationLock {

        private final AtomicInteger order = new AtomicInteger();
        private volatile Long pausedUserId;
        private volatile LockPause pause;

        PausingUserOperationLock(UserRepository userRepository) {
            super(userRepository);
        }

        synchronized LockPause pauseFirstLock(Long userId) {
            pausedUserId = userId;
            order.set(0);
            pause = new LockPause(
                    new CountDownLatch(1),
                    new CountDownLatch(2),
                    new CountDownLatch(1));
            return pause;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long userId) {
            LockPause currentPause = pause;
            if (currentPause == null || !userId.equals(pausedUserId)) {
                super.lock(userId);
                return;
            }

            int currentOrder = order.incrementAndGet();
            currentPause.twoAttempts().countDown();
            super.lock(userId);
            if (currentOrder != 1) {
                return;
            }

            currentPause.firstLocked().countDown();
            try {
                if (!currentPause.releaseLatch().await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 번째 사용자 행 잠금이 제한 시간 안에 해제되지 않았습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("사용자 행 잠금 대기 중 인터럽트되었습니다.", exception);
            }
        }
    }

    private record LockPause(
            CountDownLatch firstLocked,
            CountDownLatch twoAttempts,
            CountDownLatch releaseLatch
    ) {
        void release() {
            releaseLatch.countDown();
        }
    }
}
