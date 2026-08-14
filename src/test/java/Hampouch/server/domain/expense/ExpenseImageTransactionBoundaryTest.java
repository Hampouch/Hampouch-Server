package Hampouch.server.domain.expense;

import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.service.ExpenseImageService;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * imageKey 검증(S3 HeadObject)이 실제로 트랜잭션 밖에서 실행되는지 단언한다.
 * - 실제 프록시를 거치는 @SpringBootTest에서 S3 호출 시점의 TransactionSynchronizationManager 상태로 직접 확인.
 */
@SpringBootTest
class ExpenseImageTransactionBoundaryTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseImageService expenseImageService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ExpenseRepository expenseRepository;

    @MockitoBean
    S3Client s3Client;

    @Test
    @DisplayName("create()의 imageKey 검증(S3 HeadObject)은 트랜잭션이 시작되기 전에 실행된다")
    void createValidatesImageKeyOutsideTransaction() {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                "image-boundary-create@hampouch.test", "encoded", "이미지경계생성"));
        String imageKey = "expenses/" + user.getId() + "/abc.jpg";
        AtomicBoolean transactionActiveDuringS3Call = new AtomicBoolean(true);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            transactionActiveDuringS3Call.set(TransactionSynchronizationManager.isActualTransactionActive());
            return HeadObjectResponse.builder().build();
        });

        expenseService.create(user.getId(), request(imageKey));

        assertThat(transactionActiveDuringS3Call).isFalse();
    }

    @Test
    @DisplayName("attach()의 imageKey 검증(S3 HeadObject)은 트랜잭션이 시작되기 전에 실행된다")
    void attachValidatesImageKeyOutsideTransaction() {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                "image-boundary-attach@hampouch.test", "encoded", "이미지경계첨부"));
        Expense expense = expenseRepository.saveAndFlush(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 8, 12), user));
        String imageKey = "expenses/" + user.getId() + "/abc.jpg";
        AtomicBoolean transactionActiveDuringS3Call = new AtomicBoolean(true);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            transactionActiveDuringS3Call.set(TransactionSynchronizationManager.isActualTransactionActive());
            return HeadObjectResponse.builder().build();
        });

        expenseImageService.attach(user.getId(), expense.getId(), imageKey);

        assertThat(transactionActiveDuringS3Call).isFalse();
    }

    private ExpenseCreateRequest request(String imageKey) {
        return new ExpenseCreateRequest(
                "점심",
                8000,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                LocalDate.of(2026, 8, 12),
                null,
                imageKey);
    }
}
