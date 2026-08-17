package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.event.CommunityImageDeleteEvent;
import Hampouch.server.domain.community.repository.PostImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityImageCleanupListener {

    private final PostImageRepository postImageRepository;
    private final ImagePresignService imagePresignService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteUnusedImages(CommunityImageDeleteEvent event) {
        event.imageKeys().stream()
                .distinct()
                .forEach(this::deleteIfUnused);
    }

    private void deleteIfUnused(String imageKey) {
        try {
            if (!postImageRepository.existsByImageKey(imageKey)) {
                imagePresignService.deleteObjectSafely(imageKey);
            }
        } catch (Exception e) {
            log.warn("커뮤니티 이미지 정리 실패(무시하고 진행): imageKey={}", imageKey, e);
        }
    }
}