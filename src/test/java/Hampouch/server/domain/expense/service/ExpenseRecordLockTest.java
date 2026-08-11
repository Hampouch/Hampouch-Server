package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseRecordLockTest {

    @Mock
    UserRepository userRepository;

    @Test
    @DisplayName("지출 상태 변경 전에 사용자 행을 쓰기 잠금으로 조회한다")
    void lockUserLoadsUserForUpdate() {
        User user = User.createLocalUser("lock@hampouch.test", "encoded", "잠금테스트");
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        new ExpenseRecordLock(userRepository).lockUser(1L);

        verify(userRepository).findByIdForUpdate(1L);
    }

    @Test
    @DisplayName("잠글 사용자가 없으면 USER_NOT_FOUND를 반환한다")
    void lockUserRejectsMissingUser() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ExpenseRecordLock(userRepository).lockUser(1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    }
}
