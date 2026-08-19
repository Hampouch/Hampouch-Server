package Hampouch.server.domain.expense;

import Hampouch.server.domain.expense.dto.ExpenseUpdateRequest;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.service.ExpenseDetailInserter;
import Hampouch.server.domain.expense.service.ExpenseImageService;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ExpenseDetail이 없는 지출에 메모 수정과 이미지 첨부가 동시에 최초 생성을 시도할 때,
 * ExpenseDetailAccess의 REQUIRES_NEW insert 경쟁 복구가 실 MySQL 위에서도 두 순서 모두 정상 동작하는지 고정한다.
 */
@MySqlContainerTest
@Import(ExpenseDetailConcurrentCreationMySqlTest.PausingInserterConfig.class)
class ExpenseDetailConcurrentCreationMySqlTest {

    // 이 테스트는 ExpenseDetail insert 경쟁만 다루고 날짜 범위와는 무관하므로, 지출 변경 가능 날짜
    // 규칙에 안 걸리게 항상 오늘 날짜를 쓴다.
    private static final LocalDate EXPENSE_DATE = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final String MEMO = "동시성 테스트 메모";

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseImageService expenseImageService;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    ExpenseDetailRepository expenseDetailRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    PausingExpenseDetailInserter pausingExpenseDetailInserter;

    @MockitoBean
    S3Client s3Client;

    @BeforeEach
    void stubImageUploadCheck() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
    }

    @Test
    @DisplayName("메모 insert가 먼저 커밋되면 이미지 첨부는 PK 충돌 후 재조회로 복구되고 두 필드가 모두 남는다")
    void memoInsertWinsThenImageAttachRecovers() throws Exception {
        User user = saveUser();
        Expense expense = saveExpense(user);
        String imageKey = imageKey(user);
        pausingExpenseDetailInserter.pin(PausingExpenseDetailInserter.Role.MEMO, PausingExpenseDetailInserter.Role.IMAGE);

        RaceResult result = race(
                () -> {
                    pausingExpenseDetailInserter.actAs(PausingExpenseDetailInserter.Role.MEMO);
                    expenseService.update(user.getId(), expense.getId(), updateRequestWithMemo(expense));
                    return null;
                },
                () -> {
                    pausingExpenseDetailInserter.actAs(PausingExpenseDetailInserter.Role.IMAGE);
                    expenseImageService.attach(user.getId(), expense.getId(), imageKey);
                    return null;
                });

        assertSucceeded(result);
        assertFinalDetail(expense.getId(), imageKey);
    }

    @Test
    @DisplayName("이미지 첨부 insert가 먼저 커밋되면 메모 수정은 PK 충돌 후 재조회로 복구되고 두 필드가 모두 남는다")
    void imageAttachWinsThenMemoUpdateRecovers() throws Exception {
        User user = saveUser();
        Expense expense = saveExpense(user);
        String imageKey = imageKey(user);
        pausingExpenseDetailInserter.pin(PausingExpenseDetailInserter.Role.IMAGE, PausingExpenseDetailInserter.Role.MEMO);

        RaceResult result = race(
                () -> {
                    pausingExpenseDetailInserter.actAs(PausingExpenseDetailInserter.Role.MEMO);
                    expenseService.update(user.getId(), expense.getId(), updateRequestWithMemo(expense));
                    return null;
                },
                () -> {
                    pausingExpenseDetailInserter.actAs(PausingExpenseDetailInserter.Role.IMAGE);
                    expenseImageService.attach(user.getId(), expense.getId(), imageKey);
                    return null;
                });

        assertSucceeded(result);
        assertFinalDetail(expense.getId(), imageKey);
    }

    private void assertSucceeded(RaceResult result) {
        assertThat(result.memo().error()).as("메모 수정 결과").isNull();
        assertThat(result.image().error()).as("이미지 첨부 결과").isNull();
    }

    private void assertFinalDetail(Long expenseId, String imageKey) {
        ExpenseDetail detail = expenseDetailRepository.findByExpenseId(expenseId).orElseThrow();
        assertThat(detail.getMemo()).isEqualTo(MEMO);
        assertThat(detail.getImageKey()).isEqualTo(imageKey);
    }

    private User saveUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.createLocalUser(
                "detail-race-" + suffix + "@hampouch.test", "encoded", "동시성" + suffix));
    }

    private Expense saveExpense(User user) {
        return expenseRepository.saveAndFlush(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, EXPENSE_DATE, user));
    }

    private String imageKey(User user) {
        return "expenses/" + user.getId() + "/" + UUID.randomUUID() + ".jpg";
    }

    private ExpenseUpdateRequest updateRequestWithMemo(Expense expense) {
        return new ExpenseUpdateRequest(
                expense.getName(),
                expense.getPrice(),
                expense.getCategory(),
                null,
                expense.getEmotion(),
                null,
                expense.getExpenseDate(),
                MEMO);
    }

    private RaceResult race(Callable<Void> memoTask, Callable<Void> imageTask) throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> memoFuture = executor.submit(() -> runAfterBarrier(startBarrier, memoTask));
            Future<Outcome> imageFuture = executor.submit(() -> runAfterBarrier(startBarrier, imageTask));
            return new RaceResult(memoFuture.get(15, TimeUnit.SECONDS), imageFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome runAfterBarrier(CyclicBarrier barrier, Callable<Void> task) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            task.call();
            return new Outcome(null);
        } catch (Throwable error) {
            return new Outcome(error);
        }
    }

    private record Outcome(Throwable error) {
    }

    private record RaceResult(Outcome memo, Outcome image) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PausingInserterConfig {

        @Bean
        @Primary
        PausingExpenseDetailInserter pausingExpenseDetailInserter(ExpenseDetailRepository expenseDetailRepository) {
            return new PausingExpenseDetailInserter(expenseDetailRepository);
        }
    }

    /**
     * insert 순서를 결정적으로 고정하는 테스트 더블.
     * 두 스레드는 먼저 CyclicBarrier에서 만나 각자 자기 스냅샷 기준으로는 행이 없다고 이미 판단한 뒤임을 보장하고,
     * 그 다음 패자만 승자의 실제 insert(flush)가 끝날 때까지 대기해 PK 충돌을 결정적으로 재현한다.
     */
    static class PausingExpenseDetailInserter extends ExpenseDetailInserter {

        enum Role { MEMO, IMAGE }

        private final ThreadLocal<Role> currentRole = new ThreadLocal<>();
        private volatile CyclicBarrier bothArrived;
        private volatile CountDownLatch winnerDone;
        private volatile Role winnerRole;
        private volatile Role loserRole;

        PausingExpenseDetailInserter(ExpenseDetailRepository expenseDetailRepository) {
            super(expenseDetailRepository);
        }

        void pin(Role winner, Role loser) {
            this.winnerRole = winner;
            this.loserRole = loser;
            this.bothArrived = new CyclicBarrier(2);
            this.winnerDone = new CountDownLatch(1);
        }

        void actAs(Role role) {
            currentRole.set(role);
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void insert(Expense expense) {
            CyclicBarrier barrier = this.bothArrived;
            if (barrier == null) {
                super.insert(expense);
                return;
            }

            awaitBarrier(barrier);
            Role role = currentRole.get();
            if (role == loserRole) {
                awaitLatch(winnerDone);
            }
            try {
                super.insert(expense);
            } finally {
                if (role == winnerRole) {
                    winnerDone.countDown();
                }
            }
        }

        private void awaitBarrier(CyclicBarrier barrier) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("insert 동시 도착 대기 실패", e);
            }
        }

        private void awaitLatch(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("승자 insert 완료 대기 시간 초과");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("승자 insert 완료 대기 중 인터럽트", e);
            }
        }
    }
}
