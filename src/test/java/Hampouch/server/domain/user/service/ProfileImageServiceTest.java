package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.ProfileImagePresignRequest;
import Hampouch.server.domain.user.dto.response.ProfileImageAttachResponse;
import Hampouch.server.domain.user.dto.response.ProfileImagePresignResponse;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserStatus;
import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProfileImageService 단위 테스트. S3Presigner/S3Client/UserRepository/ApplicationEventPublisher는 Mockito 목 — 실제 S3/DB 호출 없음.
 * bucket/region은 @Value로 주입되는 필드라 ReflectionTestUtils로 직접 채운다(Spring 컨텍스트 없이 순수 단위 테스트).
 * 옛 S3 객체 삭제는 ProfileImageCleanupListener가 커밋 이후에 수행하므로, 여기서는 이벤트 발행 여부만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ProfileImageServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;

    @Mock
    S3Presigner s3Presigner;
    @Mock
    S3Client s3Client;
    @Mock
    UserRepository userRepository;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    PresignedPutObjectRequest presignedPutObjectRequest;

    private ProfileImageService service() {
        ProfileImageService service = new ProfileImageService(s3Presigner, s3Client, userRepository, eventPublisher);
        ReflectionTestUtils.setField(service, "bucket", "hampouch-bucket");
        ReflectionTestUtils.setField(service, "region", "ap-northeast-2");
        // attach()가 self(프록시 자기참조)를 통해 attachLocked()를 호출하므로 단위 테스트에서도 자기 자신을 채워준다.
        ReflectionTestUtils.setField(service, "self", service);
        return service;
    }

    private static User user(Long id) {
        User user = User.createLocalUser("u" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static User deletedUser(Long id) {
        User user = user(id);
        ReflectionTestUtils.setField(user, "status", UserStatus.DELETED);
        return user;
    }

    // ---------- presign ----------

    @Test
    @DisplayName("정상 요청이면 userId를 key 접두어로 심은 imageKey/uploadUrl/expiresInSeconds(600)를 발급한다")
    void presign_returnsImageKeyAndUploadUrl() throws Exception {
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url())
                .thenReturn(new URI("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/profile/1/abc.jpg?X-Amz-Signature=xxx").toURL());

        var req = new ProfileImagePresignRequest("image/jpeg", 1000L);
        ProfileImagePresignResponse res = service().presign(OWNER, req);

        assertThat(res.imageKey()).startsWith("profile/" + OWNER + "/").endsWith(".jpg");
        assertThat(res.uploadUrl()).contains("hampouch-bucket");
        assertThat(res.expiresInSeconds()).isEqualTo(600);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("파일 크기가 10MB를 초과하면 400(USER_PROFILE_IMAGE_SIZE_EXCEEDED)을 던지고 S3는 호출하지 않는다")
    void presign_rejectsFileOverSizeLimit() {
        var req = new ProfileImagePresignRequest("image/jpeg", 10 * 1024 * 1024 + 1L);

        assertThatThrownBy(() -> service().presign(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_SIZE_EXCEEDED);
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("S3Presigner가 예상 못한 이유로 실패하면 500(USER_PROFILE_IMAGE_UPLOAD_FAILED)으로 감싸 던진다")
    void presign_wrapsUnexpectedPresignerFailure() {
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(new RuntimeException("boom"));

        var req = new ProfileImagePresignRequest("image/jpeg", 1000L);

        assertThatThrownBy(() -> service().presign(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_UPLOAD_FAILED);
    }

    // ---------- validateOwnedAndUploaded ----------

    @Test
    @DisplayName("imageKey가 이 userId 접두어로 시작하고 HeadObject도 성공하면 예외 없이 통과한다")
    void validateOwnedAndUploaded_passesWhenOwnedAndHeadObjectSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        service().validateOwnedAndUploaded(OWNER, "profile/" + OWNER + "/abc.jpg");

        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("HeadObject가 NoSuchKeyException을 던지면(업로드 안 됨) 400(USER_PROFILE_IMAGE_NOT_UPLOADED)으로 변환한다")
    void validateOwnedAndUploaded_throwsWhenNotUploaded() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> service().validateOwnedAndUploaded(OWNER, "profile/" + OWNER + "/missing.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
    }

    @Test
    @DisplayName("imageKey가 다른 userId 접두어면 S3는 확인하지도 않고 403(USER_PROFILE_IMAGE_KEY_FORBIDDEN)을 던진다")
    void validateOwnedAndUploaded_throwsWhenKeyOwnedByAnotherUser() {
        assertThatThrownBy(() -> service().validateOwnedAndUploaded(OWNER, "profile/" + OTHER + "/abc.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_KEY_FORBIDDEN);
        verifyNoInteractions(s3Client);
    }

    // ---------- attach ----------

    @Test
    @DisplayName("처음 업로드하면 public URL을 조합해 profileImageUrl/profileImageKey에 반영하고 삭제 이벤트는 발행하지 않는다")
    void attach_attachesPublicUrlAndSkipsS3DeleteWhenNoPreviousImage() {
        User user = user(OWNER);
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        ProfileImageAttachResponse response = service().attach(OWNER, "profile/" + OWNER + "/abc.jpg");

        assertThat(user.getProfileImageKey()).isEqualTo("profile/" + OWNER + "/abc.jpg");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/profile/" + OWNER + "/abc.jpg");
        assertThat(response.imageUrl()).isEqualTo(user.getProfileImageUrl());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("기존에 다른 이미지가 붙어있었다면 교체 후 그 옛 imageKey로 삭제 이벤트를 발행한다(커밋 이후 정리는 ProfileImageCleanupListener가 담당)")
    void attach_deletesOldS3ObjectWhenReplacingExistingImage() {
        User user = user(OWNER);
        user.attachProfileImage("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/profile/" + OWNER + "/old.jpg", "profile/" + OWNER + "/old.jpg");
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        service().attach(OWNER, "profile/" + OWNER + "/new.jpg");

        ArgumentCaptor<ProfileImageDeleteEvent> captor = ArgumentCaptor.forClass(ProfileImageDeleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().imageKey()).isEqualTo("profile/" + OWNER + "/old.jpg");
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던진다")
    void attach_throws403WhenUserDeleted() {
        User user = deletedUser(OWNER);
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertThatThrownBy(() -> service().attach(OWNER, "profile/" + OWNER + "/abc.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
    }

    @Test
    @DisplayName("다른 유저 접두어의 imageKey를 붙이려 하면 403(USER_PROFILE_IMAGE_KEY_FORBIDDEN)을 던지고 잠금 조회는 하지 않는다")
    void attach_forbiddenWhenImageKeyOwnedByAnotherUser() {
        assertThatThrownBy(() -> service().attach(OWNER, "profile/" + OTHER + "/abc.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_KEY_FORBIDDEN);
        verifyNoInteractions(userRepository);
    }

    // ---------- remove ----------

    @Test
    @DisplayName("기존 이미지가 있으면 profileImageUrl/profileImageKey를 비우고 그 imageKey로 삭제 이벤트를 발행한다")
    void remove_clearsImageAndDeletesS3ObjectWhenImagePresent() {
        User user = user(OWNER);
        user.attachProfileImage("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/profile/" + OWNER + "/abc.jpg", "profile/" + OWNER + "/abc.jpg");
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));

        service().remove(OWNER);

        assertThat(user.getProfileImageUrl()).isNull();
        assertThat(user.getProfileImageKey()).isNull();
        ArgumentCaptor<ProfileImageDeleteEvent> captor = ArgumentCaptor.forClass(ProfileImageDeleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().imageKey()).isEqualTo("profile/" + OWNER + "/abc.jpg");
    }

    @Test
    @DisplayName("기존 이미지가 없으면(이미 기본 이미지) 삭제 이벤트를 발행하지 않는다 - 멱등")
    void remove_skipsS3DeleteWhenNoImageWasAttached() {
        User user = user(OWNER);
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));

        service().remove(OWNER);

        assertThat(user.getProfileImageUrl()).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던지고 삭제 이벤트도 발행하지 않는다")
    void remove_swallowsS3DeleteFailure() {
        User user = deletedUser(OWNER);
        when(userRepository.findByIdForUpdate(OWNER)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().remove(OWNER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
        verifyNoInteractions(eventPublisher);
    }
}
