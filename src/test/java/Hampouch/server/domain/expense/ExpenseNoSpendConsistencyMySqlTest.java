package Hampouch.server.domain.expense;

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
import Hampouch.server.domain.expense.service.ExpenseRecordLock;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;

@MySqlContainerTest
class ExpenseNoSpendConsistencyMySqlTest {

    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 8, 12);

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    UserRepository userRepository;

    @MockitoSpyBean
    ExpenseRecordLock expenseRecordLock;

    @Test
    @DisplayName("무지출 기록이 먼저 사용자 행을 잠그면 지출 생성은 커밋까지 기다리고 최종 상태에는 지출만 남는다")
    void createWaitsForNoSpendAndRemovesItAfterLockRelease() throws Exception {
        User user = saveUser("no-spend-first", "무지출선행");
        LockPause pause = pauseFirstLock(user.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

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

    @Test
    @DisplayName("지출 생성이 먼저 사용자 행을 잠그면 무지출 요청은 커밋까지 기다린 뒤 지출을 보고 추가 저장하지 않는다")
    void noSpendWaitsForCreateAndDoesNotPersistAfterLockRelease() throws Exception {
        User user = saveUser("expense-first", "지출선행");
        LockPause pause = pauseFirstLock(user.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

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

    @Test
    @DisplayName("삭제가 먼저 사용자 행을 잠그면 수정은 삭제 커밋까지 기다린 뒤 404로 끝나고 지출을 되살리지 않는다")
    void updateWaitsForDeleteAndDoesNotRestoreDeletedExpense() throws Exception {
        User user = saveUser("delete-first", "삭제선행");
        Expense expense = saveExpense(user, "삭제할 지출");
        LockPause pause = pauseFirstLock(user.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

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

    @Test
    @DisplayName("수정이 먼저 사용자 행을 잠그면 삭제는 수정 커밋까지 기다린 뒤 최신 지출을 삭제한다")
    void deleteWaitsForUpdateAndDeletesCommittedExpense() throws Exception {
        User user = saveUser("update-first", "수정선행");
        Expense expense = saveExpense(user, "수정할 지출");
        LockPause pause = pauseFirstLock(user.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

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

    private User saveUser(String emailPrefix, String nickname) {
        return userRepository.saveAndFlush(User.createLocalUser(
                emailPrefix + "@hampouch.test", "encoded", nickname));
    }

    private ExpenseCreateRequest request(String name) {
        return new ExpenseCreateRequest(
                name,
                8000,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                RECORD_DATE,
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

    private LockPause pauseFirstLock(Long userId) {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch twoAttempts = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();

        doAnswer(invocation -> {
            int currentOrder = order.incrementAndGet();
            twoAttempts.countDown();
            invocation.callRealMethod();
            if (currentOrder == 1) {
                firstLocked.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 번째 사용자 행 잠금이 제한 시간 안에 해제되지 않았습니다.");
                }
            }
            return null;
        }).when(expenseRecordLock).lockUser(userId);

        return new LockPause(firstLocked, twoAttempts, release);
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
