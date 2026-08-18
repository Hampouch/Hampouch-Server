package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프로필 이미지가 교체/삭제될 때 옛 S3 객체를 커밋 이후에 정리
 */
@Component
@RequiredArgsConstructor
public class ProfileImageCleanupListener {

    private final ProfileImageService profileImageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteOldProfileImage(ProfileImageDeleteEvent event) {
        profileImageService.deleteObjectSafely(event.imageKey());
    }
}
