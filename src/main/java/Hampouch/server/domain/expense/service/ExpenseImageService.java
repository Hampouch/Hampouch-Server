package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.ExpenseImagePresignRequest;
import Hampouch.server.domain.expense.dto.ExpenseImagePresignResponse;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 지출 이미지 presign 발급 + 업로드 반영/삭제/조회.
 * imageUrl은 DB에 저장하지 않는다 — 조회 시점마다 presignGetUrl()로 새로 서명한다.
 * validateOwnedAndUploaded()는 attach()와 ExpenseService.create()가 공유한다.
 * ExpenseService를 의존하지 않고 ExpenseRepository로 직접 소유권을 확인해 순환 참조를 피한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseImageService {

    private static final String KEY_PREFIX = "expenses/";
    private static final Duration UPLOAD_URL_EXPIRATION = Duration.ofMinutes(10);
    private static final Duration VIEW_URL_EXPIRATION = Duration.ofMinutes(10);
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB, community와 동일 상한

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final ExpenseRepository expenseRepository;
    private final ExpenseDetailRepository expenseDetailRepository;
    private final ExpenseDetailAccess expenseDetailAccess; // attach()의 get-or-create 동시성 경쟁 방지(#8) — remove()는 그대로 expenseDetailRepository 사용

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.region}")
    private String region;

    /**
     * POST /expenses/photos/presigned — 생성 전 업로드는 expenseId 없이, 기존 지출 교체는 expenseId와 함께.
     * key에 userId 접두어를 심어 소유권 위조를 attach()/create()가 접두어 비교만으로 걸러낼 수 있게 한다.
     */
    public ExpenseImagePresignResponse presign(Long userId, Long expenseId, ExpenseImagePresignRequest request) {
        if (expenseId != null) {
            loadOwned(userId, expenseId);
        }
        return presignInternal(userId, request);
    }

    /** PATCH /expenses/{expenseId}/photos — get-or-create는 ExpenseDetailAccess에 위임, 교체 시 옛 S3 객체 정리(실패해도 요청은 성공). */
    @Transactional
    public void attach(Long userId, Long expenseId, String imageKey) {
        Expense expense = loadOwned(userId, expenseId);
        validateOwnedAndUploaded(userId, imageKey);

        ExpenseDetail detail = expenseDetailAccess.getOrCreate(expense);
        String oldImageKey = detail.getImageKey();
        detail.attachImage(imageKey);
        if (oldImageKey != null && !oldImageKey.equals(imageKey)) {
            deleteObjectSafely(oldImageKey);
        }
    }

    /** DELETE /expenses/{expenseId}/photos — 상세 행 없으면 멱등 성공. DB 필드와 별개로 S3 객체도 지운다(안 지우면 고아 객체 누적). */
    @Transactional
    public void remove(Long userId, Long expenseId) {
        loadOwned(userId, expenseId);
        expenseDetailRepository.findByExpenseId(expenseId).ifPresent(detail -> {
            String oldImageKey = detail.getImageKey();
            detail.removeImage();
            if (oldImageKey != null) {
                deleteObjectSafely(oldImageKey);
            }
        });
    }

    /**
     * imageKey 소유권(접두어)과 S3 HeadObject 실제 업로드 여부를 확인 — attach()/create()가 공유.
     * NOT_SUPPORTED로 호출부 트랜잭션을 일시 중단시켜, S3 호출 동안 DB 커넥션을 붙잡지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void validateOwnedAndUploaded(Long userId, String imageKey) {
        String ownerPrefix = KEY_PREFIX + userId + "/";
        if (!imageKey.startsWith(ownerPrefix)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_KEY_FORBIDDEN);
        }
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(imageKey).build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED);
        }
    }

    /**
     * getDetail() 응답용 이미지 조회 URL을 조회 시점마다 새로 서명한다(imageUrl은 저장 안 함).
     * 소유권 재검증은 안 함 — 호출부가 loadOwned()로 이미 확인한 뒤에만 부른다는 전제.
     */
    public String presignGetUrl(String imageKey) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(imageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(VIEW_URL_EXPIRATION)
                .getObjectRequest(objectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    private ExpenseImagePresignResponse presignInternal(Long userId, ExpenseImagePresignRequest request) {
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
            return ExpenseImagePresignResponse.of(imageKey, presignedRequest.url().toString(), UPLOAD_URL_EXPIRATION.toSeconds());
        } catch (Exception e) {
            log.error("지출 이미지 presigned URL 발급 실패: contentType={}, size={}", request.contentType(), request.size(), e);
            throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_UPLOAD_FAILED);
        }
    }

    private String buildImageKey(Long userId, String contentType) {
        return KEY_PREFIX + userId + "/" + UUID.randomUUID() + resolveExtension(contentType);
    }

    /** ExpenseService.loadOwned()와 동일한 조회+소유권 검증 — 순환 의존을 피하려고 ExpenseRepository로 직접 재구현 */
    private Expense loadOwned(Long userId, Long expenseId) {
        Expense expense = expenseRepository.findByIdAndStatus(expenseId, ExpenseStatus.ACTIVE)
                .orElseThrow(() -> new CustomException(ExpenseErrorCode.EXPENSE_NOT_FOUND));
        if (!expense.isOwnedBy(userId)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_FORBIDDEN);
        }
        return expense;
    }

    private void validateFileSize(Long size) {
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_SIZE_EXCEEDED);
        }
    }

    /** 정리 실패로 본 요청까지 실패시키지 않도록 예외를 삼키고 경고 로그만 남긴다. */
    private void deleteObjectSafely(String imageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(imageKey).build());
        } catch (Exception e) {
            log.warn("지출 이미지 S3 객체 삭제 실패(무시하고 진행): imageKey={}", imageKey, e);
        }
    }

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_UPLOAD_FAILED); // @Pattern이 이미 걸러 정상 흐름에선 도달 불가
        };
    }
}
