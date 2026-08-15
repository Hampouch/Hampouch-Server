package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.NicknameUpdateRequest;
import Hampouch.server.domain.user.dto.response.NicknameUpdateResponse;
import Hampouch.server.domain.user.dto.response.UserMeResponse;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserStatus;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 서비스 상태 전이·검증. 리포지토리는 Mockito 목 — DB 불필요
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    UserRepository userRepository;
    @Mock
    UserOperationLock userOperationLock;

    private UserService service() {
        return new UserService(userRepository, userOperationLock);
    }

    // ---------- getMyInfo ----------

    @Test
    @DisplayName("정상 조회면 닉네임·프로필이미지·이메일에서 파생한 handle을 반환한다")
    void getMyInfo_returnsNicknameProfileImageAndDerivedHandle() {
        User user = user(USER_ID, "hampochi_minjun@hampouch.com", "절약왕 민준");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserMeResponse response = service().getMyInfo(USER_ID);

        assertThat(response.nickname()).isEqualTo("절약왕 민준");
        assertThat(response.profileImageUrl()).isNull();
        assertThat(response.handle()).isEqualTo("hampochi_minjun");
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던진다")
    void getMyInfo_throws403WhenUserDeleted() {
        User user = deletedUser(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().getMyInfo(USER_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
    }

    @Test
    @DisplayName("존재하지 않는 회원이면 404(USER_NOT_FOUND)를 던진다")
    void getMyInfo_throws404WhenUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getMyInfo(USER_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    }

    // ---------- updateNickname ----------

    @Test
    @DisplayName("정상 변경이면 바뀐 닉네임을 반환한다")
    void updateNickname_returnsChangedNickname() {
        User user = user(USER_ID, "user1@hampouch.com", "기존닉네임");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        NicknameUpdateResponse response = service().updateNickname(USER_ID, new NicknameUpdateRequest("새닉네임"));

        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(user.getNickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("기존 닉네임과 같은 값이면 중복검사를 건너뛴다")
    void updateNickname_skipsDuplicateCheckWhenNicknameUnchanged() {
        User user = user(USER_ID, "user1@hampouch.com", "동일닉네임");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        NicknameUpdateResponse response = service().updateNickname(USER_ID, new NicknameUpdateRequest("동일닉네임"));

        assertThat(response.nickname()).isEqualTo("동일닉네임");
        verify(userRepository, never()).existsByNickname(any());
    }

    @Test
    @DisplayName("다른 회원이 이미 쓰는 닉네임이면 409(USER_NICKNAME_ALREADY_EXISTS)를 던진다")
    void updateNickname_throws409WhenNicknameAlreadyExists() {
        User user = user(USER_ID, "user1@hampouch.com", "기존닉네임");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("중복닉네임")).thenReturn(true);

        assertThatThrownBy(() -> service().updateNickname(USER_ID, new NicknameUpdateRequest("중복닉네임")))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던진다")
    void updateNickname_throws403WhenUserDeleted() {
        User user = deletedUser(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().updateNickname(USER_ID, new NicknameUpdateRequest("새닉네임")))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
    }

    @Test
    @DisplayName("getUser()로 조회하기 전에 UserOperationLock으로 사용자 행을 먼저 잠근다")
    void updateNickname_locksUserBeforeReadingIt() {
        User user = user(USER_ID, "user1@hampouch.com", "기존닉네임");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service().updateNickname(USER_ID, new NicknameUpdateRequest("새닉네임"));

        InOrder order = inOrder(userOperationLock, userRepository);
        order.verify(userOperationLock).lock(USER_ID);
        order.verify(userRepository).findById(USER_ID);
    }

    @Test
    @DisplayName("사전검사를 통과한 직후 동시에 다른 회원이 선점하면(saveAndFlush의 DataIntegrityViolationException) 409로 변환한다")
    void updateNickname_throws409WhenConcurrentSaveHitsUniqueConstraint() {
        // existsByNickname 사전 검사는 통과했지만, saveAndFlush 시점에 다른 트랜잭션이 먼저 커밋되어
        // uk_user_nickname 제약을 위반하는 경쟁 상태를 재현한다.
        User user = user(USER_ID, "user1@hampouch.com", "기존닉네임");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("경합닉네임")).thenReturn(false);
        when(userRepository.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("uk_user_nickname"));

        assertThatThrownBy(() -> service().updateNickname(USER_ID, new NicknameUpdateRequest("경합닉네임")))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
    }

    private static User user(Long id, String email, String nickname) {
        User user = User.createLocalUser(email, "encoded", nickname);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static User deletedUser(Long id) {
        User user = user(id, "deleted@hampouch.com", "탈퇴예정");
        ReflectionTestUtils.setField(user, "status", UserStatus.DELETED);
        return user;
    }
}
