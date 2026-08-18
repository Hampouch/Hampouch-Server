package Hampouch.server.domain.user;

import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.ProfileImageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * attach()의 imageKey 검증(S3 HeadObject)이 실제로 트랜잭션 밖에서 실행되는지 단언한다.
 * ExpenseImageTransactionBoundaryTest와 동일한 기법: 실제 프록시를 거치는 @SpringBootTest에서
 * S3 호출 시점의 TransactionSynchronizationManager 상태로 직접 확인.
 */
@SpringBootTest
class ProfileImageTransactionBoundaryTest {

    @Autowired
    ProfileImageService profileImageService;
    @Autowired
    UserRepository userRepository;

    @MockitoBean
    S3Client s3Client;

    @Test
    @DisplayName("attach()의 imageKey 검증(S3 HeadObject)은 트랜잭션이 시작되기 전에 실행된다")
    void attachValidatesImageKeyOutsideTransaction() {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                "profile-image-boundary-attach@hampouch.test", "encoded", "이미지경계프로필"));
        String imageKey = "profile/" + user.getId() + "/abc.jpg";
        AtomicBoolean transactionActiveDuringS3Call = new AtomicBoolean(true);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            transactionActiveDuringS3Call.set(TransactionSynchronizationManager.isActualTransactionActive());
            return HeadObjectResponse.builder().build();
        });

        profileImageService.attach(user.getId(), imageKey);

        assertThat(transactionActiveDuringS3Call).isFalse();
    }
}
