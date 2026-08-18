package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.ProfileImagePresignRequest;
import Hampouch.server.domain.user.dto.response.ProfileImageAttachResponse;
import Hampouch.server.domain.user.dto.response.ProfileImagePresignResponse;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.event.ProfileImageDeleteEvent;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 프로필 이미지 presign 발급 + 업로드 반영/초기화.
 * Community 이미지와 동일하게 버킷이 public-read라고 전제하고 영구 public URL을 User.profileImageUrl에 그대로 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileImageService {

    private static final String KEY_PREFIX = "profile/";
    private static final Duration UPLOAD_URL_EXPIRATION = Duration.ofMinutes(10);
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB, community/expense와 동일 상한

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.region}")
    private String region;

    /** attach()가 S3 확인 뒤 attachLocked()를 프록시로 재호출하기 위한 자기 참조 — 같은 빈 안의 this.호출은 @Transactional을 우회한다. */
    @Lazy
    @Autowired
    private ProfileImageService self;

    /**
     * POST /users/me/profile/presigned — key에 userId 접두어를 심어 소유권 위조를 attach()가 접두어 비교만으로 걸러낼 수 있게 한다.
     * 탈퇴 회원 체크는 하지 않는다(User 조회 자체가 없음) - PATCH/DELETE와 달리 DB에 아무것도 반영되지 않기 때문.
     */
    public ProfileImagePresignResponse presign(Long userId, ProfileImagePresignRequest request) {
        validateFileSize(request.size());
        String imageKey = buildImageKey(userId, request.contentType());

        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageKey)
                    .contentType(request.contentType())
                    .contentLength(request.size())
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(UPLOAD_URL_EXPIRATION)
                    .putObjectRequest(objectRequest)
                    .build();

            PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
            return ProfileImagePresignResponse.of(imageKey, presignedRequest.url().toString(), UPLOAD_URL_EXPIRATION.toSeconds());
        } catch (Exception e) {
            log.error("프로필 이미지 presigned URL 발급 실패: contentType={}, size={}", request.contentType(), request.size(), e);
            throw new CustomException(UserErrorCode.USER_PROFILE_IMAGE_UPLOAD_FAILED);
        }
    }

    /** PATCH /users/me/profile — S3 확인을 트랜잭션 밖에서 먼저 끝낸 뒤 self로 attachLocked()를 호출한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProfileImageAttachResponse attach(Long userId, String imageKey) {
        validateOwnedAndUploaded(userId, imageKey);
        return self.attachLocked(userId, imageKey);
    }

    /**
     * 교체 시 옛 S3 객체 정리는 ProfileImageCleanupListener가 커밋 이후 수행
     */
    @Transactional
    public ProfileImageAttachResponse attachLocked(Long userId, String imageKey) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        String oldImageKey = user.getProfileImageKey();
        String imageUrl = buildPublicUrl(imageKey);
        user.attachProfileImage(imageUrl, imageKey);

        if (oldImageKey != null && !oldImageKey.equals(imageKey)) {
            eventPublisher.publishEvent(new ProfileImageDeleteEvent(oldImageKey));
        }

        return ProfileImageAttachResponse.of(imageUrl);
    }

    /** DELETE /users/me/profile — 기본 이미지로 되돌린다. DB 필드와 별개로 S3 객체도 지우되, 커밋 이후에(락 밖에서) 정리한다. */
    @Transactional
    public void remove(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        String oldImageKey = user.getProfileImageKey();
        user.resetProfileImage();

        if (oldImageKey != null) {
            eventPublisher.publishEvent(new ProfileImageDeleteEvent(oldImageKey));
        }
    }

    /**
     * imageKey 소유권(접두어)과 S3 HeadObject 실제 업로드 여부를 확인.
     * NOT_SUPPORTED로 호출부 트랜잭션을 일시 중단시켜, S3 호출 동안 DB 커넥션을 붙잡지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void validateOwnedAndUploaded(Long userId, String imageKey) {
        String ownerPrefix = KEY_PREFIX + userId + "/";
        if (!imageKey.startsWith(ownerPrefix)) {
            throw new CustomException(UserErrorCode.USER_PROFILE_IMAGE_KEY_FORBIDDEN);
        }
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(imageKey).build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
        }
    }

    /** 버킷이 퍼블릭 읽기를 허용하므로, 업로드 후 그대로 접근 가능한 조회용 URL을 S3의 표준 URL 형식으로 직접 조합(서명 불필요, 만료 없음) — Community의 ImagePresignService.buildPublicUrl()과 동일 방식. */
    private String buildPublicUrl(String imageKey) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + imageKey;
    }

    private String buildImageKey(Long userId, String contentType) {
        return KEY_PREFIX + userId + "/" + UUID.randomUUID() + resolveExtension(contentType);
    }

    private void validateFileSize(Long size) {
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new CustomException(UserErrorCode.USER_PROFILE_IMAGE_SIZE_EXCEEDED);
        }
    }

    /** 정리 실패로 본 요청까지 실패시키지 않도록 예외를 삼키고 경고 로그만 남긴다. ProfileImageCleanupListener가 커밋 이후에 호출한다. */
    void deleteObjectSafely(String imageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(imageKey).build());
        } catch (Exception e) {
            log.warn("프로필 이미지 S3 객체 삭제 실패(무시하고 진행): imageKey={}", imageKey, e);
        }
    }

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new CustomException(UserErrorCode.USER_PROFILE_IMAGE_UPLOAD_FAILED); // @Pattern이 이미 걸러 정상 흐름에선 도달 불가
        };
    }
}
