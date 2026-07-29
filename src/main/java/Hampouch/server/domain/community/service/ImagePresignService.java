package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.dto.request.PresignRequest;
import Hampouch.server.domain.community.dto.response.PresignResponse;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommunityErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 커뮤니티 게시글용 이미지 presigned URL 발급
 * 프론트에서 이 URL로 S3에 직접 PUT 업로드하고, 서버는 URL 발급만 담당한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePresignService {

    private static final String KEY_PREFIX = "community/posts/";
    private static final Duration UPLOAD_URL_EXPIRATION = Duration.ofMinutes(10);

    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.region}")
    private String region;

    public PresignResponse presign(PresignRequest request) {
        List<PresignResponse.FileResult> results = request.files().stream()
                .map(this::presignSingleFile)
                .toList();

        return PresignResponse.of(results);
    }

    private PresignResponse.FileResult presignSingleFile(PresignRequest.FileInfo fileInfo) {
        String extension = resolveExtension(fileInfo.contentType());
        String imageKey = KEY_PREFIX + UUID.randomUUID() + extension;

        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageKey)
                    .contentType(fileInfo.contentType())
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(UPLOAD_URL_EXPIRATION)
                    .putObjectRequest(objectRequest)
                    .build();

            PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

            String uploadUrl = presignedRequest.url().toString();
            String imageUrl = buildPublicUrl(imageKey);

            return PresignResponse.FileResult.of(uploadUrl, imageKey, imageUrl);
        } catch (Exception e) {
            log.error("presigned URL 발급 실패: contentType={}", fileInfo.contentType(), e);
            throw new CustomException(CommunityErrorCode.COMMUNITY_IMAGE_UPLOAD_FAILED);
        }
    }

    /** 버킷이 퍼블릭 읽기를 허용하므로, 업로드 후 그대로 접근 가능한 조회용 URL을 S3의 표준 URL 형식으로 직접 조합 (서명 불필요, 만료 없음) */
    private String buildPublicUrl(String imageKey) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + imageKey;
    }

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new CustomException(CommunityErrorCode.COMMUNITY_IMAGE_UPLOAD_FAILED);
        };
    }
}