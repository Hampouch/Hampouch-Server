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
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 지출 이미지 presign 발급 + 업로드 반영/삭제.
 * 단일 image upload 및 PATCH 단계에서 HeadObject로 실제 업로드 여부를 확인
 * resolveImageUrl()은 이 서비스의 attach()뿐 아니라 ExpenseService.create()/update()도 그대로 재사용한다 —
 * imageKey를 검증하고 imageUrl을 만든다는 로직이 PATCH 경로와 생성/수정 경로에서 중복되지 않도록.
 * ExpenseService를 의존하지 않고 ExpenseRepository로 직접 소유권을 확인하여 순환 참조 방지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseImageService {

    private static final String KEY_PREFIX = "expenses/";
    private static final Duration UPLOAD_URL_EXPIRATION = Duration.ofMinutes(10);
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB, community와 동일 상한

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final ExpenseRepository expenseRepository;
    private final ExpenseDetailRepository expenseDetailRepository;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.region}")
    private String region;

    /**
     * POST /expenses/photos/presigned — expenseId는 query param(선택). 지출 생성 전 업로드는 expenseId 없이,
     * 기존 지출 이미지 교체는 expenseId를 실어 보내는 방식으로 경로 하나에 합쳤다.
     */
    public ExpenseImagePresignResponse presign(Long userId, Long expenseId, ExpenseImagePresignRequest request) {
        if (expenseId != null) {
            loadOwned(userId, expenseId);
        }
        return presignInternal(request);
    }

    /** PATCH /expenses/{expenseId}/photos — 없으면 새로 만들고, 있으면 attachImage()로 갱신(get-or-create). */
    @Transactional
    public void attach(Long userId, Long expenseId, String imageKey) {
        Expense expense = loadOwned(userId, expenseId);
        String imageUrl = resolveImageUrl(imageKey);

        ExpenseDetail detail = expenseDetailRepository.findByExpenseId(expenseId)
                .orElseGet(() -> expenseDetailRepository.save(ExpenseDetail.of(expense, null)));
        detail.attachImage(imageKey, imageUrl);
    }

    /** DELETE /expenses/{expenseId}/photos — 상세 행 자체가 없으면 할 일이 없어 그냥 성공 처리(멱등). */
    @Transactional
    public void remove(Long userId, Long expenseId) {
        loadOwned(userId, expenseId);
        expenseDetailRepository.findByExpenseId(expenseId).ifPresent(ExpenseDetail::removeImage);
    }

    /**
     * imageKey를 S3 HeadObject로 실존 확인하고 공개 조회 URL을 만든다.
     * ExpenseService.create()/update()가 imageKey를 받을 때도 그대로 호출한다.
     */
    public String resolveImageUrl(String imageKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(imageKey).build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED);
        }
        return buildPublicUrl(imageKey);
    }

    private ExpenseImagePresignResponse presignInternal(ExpenseImagePresignRequest request) {
        validateFileSize(request.size());
        String imageKey = KEY_PREFIX + UUID.randomUUID() + resolveExtension(request.contentType());

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

    private String buildPublicUrl(String imageKey) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + imageKey;
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
