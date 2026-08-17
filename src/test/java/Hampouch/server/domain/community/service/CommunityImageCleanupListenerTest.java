package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.event.CommunityImageDeleteEvent;
import Hampouch.server.domain.community.repository.PostImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityImageCleanupListenerTest {

    @Mock
    PostImageRepository postImageRepository;
    @Mock
    ImagePresignService imagePresignService;

    CommunityImageCleanupListener listener;

    @BeforeEach
    void setUp() {
        listener = new CommunityImageCleanupListener(
                postImageRepository,
                imagePresignService
        );
    }

    @Test
    void 더이상_참조되지않는_이미지는_S3에서_삭제한다() {
        CommunityImageDeleteEvent event = new CommunityImageDeleteEvent(
                List.of("community/posts/removed.jpg")
        );
        when(postImageRepository.existsByImageKey("community/posts/removed.jpg"))
                .thenReturn(false);

        listener.deleteUnusedImages(event);

        verify(imagePresignService)
                .deleteObjectSafely("community/posts/removed.jpg");
    }

    @Test
    void 다른_게시글이_참조중인_이미지는_S3에서_삭제하지않는다() {
        CommunityImageDeleteEvent event = new CommunityImageDeleteEvent(
                List.of("community/posts/shared.jpg")
        );
        when(postImageRepository.existsByImageKey("community/posts/shared.jpg"))
                .thenReturn(true);

        listener.deleteUnusedImages(event);

        verify(imagePresignService, never())
                .deleteObjectSafely("community/posts/shared.jpg");
    }

    @Test
    void 중복된_이미지키는_한번만_삭제한다() {
        CommunityImageDeleteEvent event = new CommunityImageDeleteEvent(
                List.of(
                        "community/posts/removed.jpg",
                        "community/posts/removed.jpg"
                )
        );
        when(postImageRepository.existsByImageKey("community/posts/removed.jpg"))
                .thenReturn(false);

        listener.deleteUnusedImages(event);

        verify(imagePresignService)
                .deleteObjectSafely("community/posts/removed.jpg");
    }

    @Test
    void 이미지_참조조회가_실패해도_예외를_전파하지않는다() {
        CommunityImageDeleteEvent event = new CommunityImageDeleteEvent(
                List.of("community/posts/removed.jpg")
        );
        when(postImageRepository.existsByImageKey("community/posts/removed.jpg"))
                .thenThrow(new RuntimeException("DB 조회 실패"));

        org.assertj.core.api.Assertions.assertThatCode(
                () -> listener.deleteUnusedImages(event)
        ).doesNotThrowAnyException();

        verify(imagePresignService, never())
                .deleteObjectSafely("community/posts/removed.jpg");
    }
}