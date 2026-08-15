package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.event.CommunityImageDeleteEvent;
import Hampouch.server.domain.community.repository.PostImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class CommunityImageCleanupListener {

    private final PostImageRepository postImageRepository;
    private final ImagePresignService imagePresignService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteUnusedImages(CommunityImageDeleteEvent event) {
        event.imageKeys().stream()
                .distinct()
                .filter(imageKey -> !postImageRepository.existsByImageKey(imageKey))
                .forEach(imagePresignService::deleteObjectSafely);
    }
}