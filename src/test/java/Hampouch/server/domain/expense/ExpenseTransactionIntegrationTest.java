package Hampouch.server.domain.expense;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseUpdateRequest;
import Hampouch.server.domain.expense.dto.NoSpendRecordRequest;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseDateLockQuery;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ExpenseTransactionIntegrationTest.PausingLockQueryConfig.class)
class ExpenseTransactionIntegrationTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ChallengeService challengeService;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ChallengeRepository challengeRepository;
    @Autowired
    PausingExpenseDateLockQuery pausingExpenseDateLockQuery;

    @Test
    @DisplayName("무지출 기록과 지출 생성·수정·삭제가 끝날 때마다 DB를 다시 조회해도 행 상태와 마지막 기록일이 유지된다")
    void noSpendAndExpenseChangesCommitAfterServiceCall() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate previousNoSpendDate = today.minusDays(1);
        User user = userRepository.save(User.createLocalUser(
                "expense-transaction@hampouch.test", "encoded", "지출트랜잭션"));
        Long userId = user.getId();

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(previousNoSpendDate));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, previousNoSpendDate)).isTrue();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated())
                .isEqualTo(previousNoSpendDate);

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isTrue();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated()).isEqualTo(today);

        ExpenseCreateResponse created = expenseService.create(userId, request("점심", 0, today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();
        assertThat(expenseRepository.findByIdAndStatus(created.expenseId(), ExpenseStatus.ACTIVE)).isPresent();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated()).isEqualTo(today);

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();

        User reloadedUser = userRepository.findById(userId).orElseThrow();
        noSpendDayRepository.save(NoSpendDay.of(reloadedUser, today));
        expenseService.update(userId, created.expenseId(), updateRequest("저녁", 1000, today));

        Expense updated = expenseRepository.findById(created.expenseId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("저녁");
        assertThat(updated.getPrice()).isEqualTo(1000);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();

        expenseService.delete(userId, created.expenseId());

        Expense deleted = expenseRepository.findById(created.expenseId()).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(ExpenseStatus.DELETED);
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated())
                .isEqualTo(previousNoSpendDate);
    }

    @Test
    @DisplayName("진행 중 챌린지가 있어도, 그 챌린지 기간 밖이면서 최종 종료된 챌린지 기간에 속하지 않는 날짜에는 지출을 생성할 수 있다")
    void activeChallengeDoesNotRestrictExpenseDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate outsideDate = today.minusDays(10);
        User user = userRepository.save(User.createLocalUser(
                "expense-active-challenge@hampouch.test", "encoded", "진행중범위밖"));
        Long userId = user.getId();
        challengeRepository.save(Challenge.builder()
                .userId(userId).durationDays(7).startDate(today)
                .budgetTotal(70000).dailyLimit(10000).build());

        ExpenseCreateResponse created = expenseService.create(userId, request("과거 지출", 8000, outsideDate));

        Expense saved = expenseRepository.findById(created.expenseId()).orElseThrow();
        assertThat(saved.getExpenseDate()).isEqualTo(outsideDate);
    }

    @Test
    @DisplayName("지출 수정이 챌린지 행 락을 획득한 뒤에는 최종 종료가 수정 트랜잭션 종료까지 기다린다")
    void closeWaitsForExpenseMutationThatAlreadyCheckedLock() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate expenseDate = today.minusDays(3);
        User user = userRepository.save(User.createLocalUser(
                "expense-close-race@hampouch.test", "encoded", "종료경쟁"));
        Long userId = user.getId();
        Expense expense = expenseRepository.save(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, expenseDate, user));
        Challenge challenge = Challenge.builder()
                .userId(userId).durationDays(7).startDate(today.minusDays(7))
                .budgetTotal(70000).dailyLimit(10000).build();
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(challenge);

        pausingExpenseDateLockQuery.pauseNextCheck();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> updateFuture = executor.submit(() ->
                    expenseService.update(userId, expense.getId(), updateRequest("저녁", 99000, expenseDate)));
            assertThat(pausingExpenseDateLockQuery.awaitCheck()).isTrue();

            CountDownLatch closeStarted = new CountDownLatch(1);
            Future<?> closeFuture = executor.submit(() -> {
                closeStarted.countDown();
                challengeService.close(userId, challenge.getId());
            });
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean closeWasBlocked;
            try {
                closeFuture.get(500, TimeUnit.MILLISECONDS);
                closeWasBlocked = false;
            } catch (TimeoutException expected) {
                closeWasBlocked = true;
            } finally {
                pausingExpenseDateLockQuery.release();
            }

            updateFuture.get(5, TimeUnit.SECONDS);
            closeFuture.get(5, TimeUnit.SECONDS);

            assertThat(closeWasBlocked).isTrue();
            assertThat(expenseRepository.findById(expense.getId()).orElseThrow().getPrice()).isEqualTo(99000);
            assertThat(challengeRepository.findById(challenge.getId()).orElseThrow().isExpenseLocked()).isTrue();
        } finally {
            pausingExpenseDateLockQuery.release();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("최종 종료된 챌린지 기간의 지출은 수정·삭제와 무지출 기록이 모두 409로 막히고, 막힌 뒤 DB의 지출 행도 그대로다")
    void closedChallengePeriodBlocksExpenseChanges() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate lockedDate = today.minusDays(3);
        User user = userRepository.save(User.createLocalUser(
                "expense-closed-challenge@hampouch.test", "encoded", "종료잠금"));
        Long userId = user.getId();
        Expense seeded = expenseRepository.save(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, lockedDate, user));

        // 잠금은 지출을 넣은 뒤에 걸린다 — 기간 중에 기록하고 끝난 뒤 종료를 누르는 실제 순서
        Challenge challenge = Challenge.builder()
                .userId(userId).durationDays(7).startDate(today.minusDays(7))
                .budgetTotal(70000).dailyLimit(10000).build();
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challenge.lockExpenseChanges(LocalDateTime.now());
        challengeRepository.save(challenge);

        assertThatThrownBy(() -> expenseService.update(userId, seeded.getId(), updateRequest("저녁", 99000, lockedDate)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);
        assertThatThrownBy(() -> expenseService.delete(userId, seeded.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);
        assertThatThrownBy(() -> expenseService.recordNoSpend(userId, new NoSpendRecordRequest(lockedDate)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);

        Expense untouched = expenseRepository.findById(seeded.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("점심");
        assertThat(untouched.getPrice()).isEqualTo(8000);
        assertThat(untouched.getStatus()).isEqualTo(ExpenseStatus.ACTIVE);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, lockedDate)).isFalse();

        // 잠긴 것은 그 기간뿐이라 기간 밖 날짜의 무지출 기록은 그대로 저장된다
        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isTrue();
    }

    private ExpenseCreateRequest request(String name, int price, LocalDate date) {
        return new ExpenseCreateRequest(
                name,
                price,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                date,
                null,
                null);
    }

    private ExpenseUpdateRequest updateRequest(String name, int price, LocalDate date) {
        return new ExpenseUpdateRequest(
                name,
                price,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                date,
                null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PausingLockQueryConfig {

        @Bean
        @Primary
        PausingExpenseDateLockQuery pausingExpenseDateLockQuery(ChallengeRepository challengeRepository) {
            return new PausingExpenseDateLockQuery(challengeRepository);
        }
    }

    static class PausingExpenseDateLockQuery implements ExpenseDateLockQuery {

        private final ChallengeRepository delegate;
        private volatile CountDownLatch checked = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        PausingExpenseDateLockQuery(ChallengeRepository delegate) {
            this.delegate = delegate;
        }

        synchronized void pauseNextCheck() {
            checked = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        boolean awaitCheck() throws InterruptedException {
            return checked.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        @Override
        public boolean isExpenseChangeProhibited(Long userId, LocalDate date) {
            boolean changeProhibited = delegate.isExpenseChangeProhibited(userId, date);
            CountDownLatch currentChecked = checked;
            CountDownLatch currentRelease = release;
            if (currentChecked.getCount() > 0) {
                currentChecked.countDown();
                try {
                    if (!currentRelease.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("잠금 확인 일시정지가 제한 시간 안에 해제되지 않았습니다.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("잠금 확인 일시정지가 중단됐습니다.", e);
                }
            }
            return changeProhibited;
        }
    }
}
