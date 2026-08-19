package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import Hampouch.server.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프로필 이미지가 교체/삭제될 때 옛 S3 객체를 커밋 이후에 정리한다.
 * 삭제 전 이 key가 여전히 어떤 유저의 현재 profileImageKey로 쓰이고 있는지 다시 확인한다 - 이 이벤트가
 * 발행된 뒤 이 정리가 실행되기 전 사이에, 동시에 들어온 다른 PATCH가 (아직 삭제되지 않은) 이 old key로
 * HeadObject를 통과하고 같은 key를 다시 자신의 profileImageKey로 반영해버릴 수 있다 - 그 경우 여기서
 * 그대로 지워버리면 DB는 이 key를 참조하는데 S3 객체는 없는 상태가 된다. Community의
 * CommunityImageCleanupListener와 동일한 이유.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageCleanupListener {

    private final ProfileImageService profileImageService;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteOldProfileImage(ProfileImageDeleteEvent event) {
        String imageKey = event.imageKey();
        try {
            if (userRepository.existsByProfileImageKey(imageKey)) {
                return;
            }
        } catch (Exception e) {
            log.warn("프로필 이미지 참조 확인 실패(삭제 건너뜀): imageKey={}", imageKey, e);
            return;
        }
        profileImageService.deleteObjectSafely(imageKey);
    }
}
