package Hampouch.server.domain.user;

import Hampouch.server.domain.user.dto.request.PasswordChangeRequest;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * changePassword()의 bcrypt matches()/encode()(무거운 연산)가 실제로 트랜잭션 밖에서 실행되는지,
 * 그리고 changePasswordLocked()의 현재 비밀번호 재검증은 트랜잭션 안에서 실행되는지 단언한다.
 * ExpenseImageTransactionBoundaryTest와 동일한 기법: 실제 프록시를 거치는 @SpringBootTest에서
 * 연산 시점의 TransactionSynchronizationManager 상태로 직접 확인한다.
 */
@SpringBootTest
class UserPasswordChangeTransactionBoundaryTest {

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("changePassword()의 사전 현재 비밀번호 검증(matches)은 트랜잭션 밖에서, changePasswordLocked()의 재검증은 트랜잭션 안에서 실행된다")
    void changePasswordValidatesCurrentPasswordOutsideThenInsideTransaction() {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                "password-boundary-matches@hampouch.test", "encoded-current", "비번경계매치"));
        // matches()는 사전 검사(락 밖)와 changePasswordLocked() 재검증(락 안), 총 두 번 호출된다 - 호출 순서대로 트랜잭션 활성 여부를 기록한다.
        List<Boolean> transactionActiveDuringMatches = new ArrayList<>();
        when(passwordEncoder.matches(any(), any())).thenAnswer(invocation -> {
            transactionActiveDuringMatches.add(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(passwordEncoder.encode(any())).thenReturn("encoded-new");

        userService.changePassword(user.getId(), new PasswordChangeRequest("current1", "newPassword1"));

        assertThat(transactionActiveDuringMatches).containsExactly(false, true);
    }

    @Test
    @DisplayName("changePassword()의 새 비밀번호 인코딩(encode)은 트랜잭션이 시작되기 전에 실행된다")
    void changePasswordEncodesNewPasswordOutsideTransaction() {
        User user = userRepository.saveAndFlush(User.createLocalUser(
                "password-boundary-encode@hampouch.test", "encoded-current", "비번경계인코드"));
        AtomicBoolean transactionActiveDuringEncode = new AtomicBoolean(true);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> {
            transactionActiveDuringEncode.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "encoded-new";
        });

        userService.changePassword(user.getId(), new PasswordChangeRequest("current1", "newPassword1"));

        assertThat(transactionActiveDuringEncode).isFalse();
    }
}
