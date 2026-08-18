package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import Hampouch.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileImageCleanupListenerTest {

    @Mock
    ProfileImageService profileImageService;
    @Mock
    UserRepository userRepository;

    ProfileImageCleanupListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProfileImageCleanupListener(profileImageService, userRepository);
    }

    @Test
    @DisplayName("더이상 참조되지 않는 이미지는 S3에서 삭제한다")
    void deleteOldProfileImage_deletesWhenNoLongerReferenced() {
        ProfileImageDeleteEvent event = new ProfileImageDeleteEvent("profile/1/old.jpg");
        when(userRepository.existsByProfileImageKey("profile/1/old.jpg")).thenReturn(false);

        listener.deleteOldProfileImage(event);

        verify(profileImageService).deleteObjectSafely("profile/1/old.jpg");
    }

    @Test
    @DisplayName("동시에 들어온 다른 요청이 같은 key를 다시 profileImageKey로 반영해 여전히 참조 중이면 S3에서 삭제하지 않는다")
    void deleteOldProfileImage_skipsWhenStillReferenced() {
        ProfileImageDeleteEvent event = new ProfileImageDeleteEvent("profile/1/old.jpg");
        when(userRepository.existsByProfileImageKey("profile/1/old.jpg")).thenReturn(true);

        listener.deleteOldProfileImage(event);

        verify(profileImageService, never()).deleteObjectSafely("profile/1/old.jpg");
    }

    @Test
    @DisplayName("이미지 참조 조회가 실패해도 예외를 전파하지 않고 삭제도 건너뛴다")
    void deleteOldProfileImage_swallowsFailureAndSkipsDeleteWhenReferenceCheckFails() {
        ProfileImageDeleteEvent event = new ProfileImageDeleteEvent("profile/1/old.jpg");
        when(userRepository.existsByProfileImageKey("profile/1/old.jpg")).thenThrow(new RuntimeException("DB 조회 실패"));

        assertThatCode(() -> listener.deleteOldProfileImage(event)).doesNotThrowAnyException();

        verify(profileImageService, never()).deleteObjectSafely("profile/1/old.jpg");
    }
}
