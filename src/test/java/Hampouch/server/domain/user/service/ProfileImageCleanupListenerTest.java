package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileImageCleanupListenerTest {

    @Mock
    ProfileImageService profileImageService;

    ProfileImageCleanupListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProfileImageCleanupListener(profileImageService);
    }

    @Test
    @DisplayName("이벤트를 받으면 해당 imageKey를_S3에서 삭제한다")
    void deleteImageFromS3EventAttached() {
        ProfileImageDeleteEvent event = new ProfileImageDeleteEvent("profile/1/old.jpg");

        listener.deleteOldProfileImage(event);

        verify(profileImageService).deleteObjectSafely("profile/1/old.jpg");
    }
}
