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
 * 단일 image upload 및 PATCH 단계에서 HeadObject로 실제 업로드 여부를 확인
 * imageUrl은 DB에 영구 저장하지 않는다 — 버킷이 완전 공개가 아니라 고정 URL을 저장해봐야 만료·권한 문제로
 * 실제로는 못 여는 링크가 될 수 있어서, 조회 시점마다 presignGetUrl()로 새 서명 URL을 발급한다
 * validateOwnedAndUploaded()는 이 서비스의 attach()뿐 아니라 ExpenseService.create()도 그대로 재사용한다 —
 * imageKey 소유권·업로드 여부를 검증하는 로직이 PATCH 경로와 생성 경로에서 중복되지 않도록.
 * ExpenseService를 의존하지 않고 ExpenseRepository로 직접 소유권을 확인하여 순환 참조 방지.
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
     * POST /expenses/photos/presigned — expenseId는 query param(선택). 지출 생성 전 업로드는 expenseId 없이,
     * 기존 지출 이미지 교체는 expenseId를 실어 보내는 방식으로 경로 하나에 합쳤다.
     * key에 userId를 접두어로 심어두는 이유: 다른 유저가 자기가 발급받지 않은 imageKey를 그대로 흉내내
     * 남의 지출에 붙이려는 시도를, attach()/create() 쪽에서 접두어 비교만으로 걸러낼 수 있게 하기 위함.
     */
    public ExpenseImagePresignResponse presign(Long userId, Long expenseId, ExpenseImagePresignRequest request) {
        if (expenseId != null) {
            loadOwned(userId, expenseId);
        }
        return presignInternal(userId, request);
    }

    /**
     * PATCH /expenses/{expenseId}/photos — 없으면 새로 만들고, 있으면 attachImage()로 갱신(get-or-create).
     * 기존에 다른 이미지가 붙어있었다면 교체 후 그 옛 S3 객체를 정리한다 — 실패해도 요청 자체는 성공 처리.
     * get-or-create는 동시 요청 경쟁에 안전한 ExpenseDetailAccess에 위임한다.
     */
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

    /**
     * DELETE /expenses/{expenseId}/photos — 상세 행 자체가 없으면 할 일이 없어 그냥 성공 처리(멱등).
     * DB 필드를 비우는 것과 별개로 실제 S3 객체도 지운다 — 안 지우면 버킷에 고아 객체가 계속 쌓인다.
     */
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
     * imageKey가 이 userId 소유인지 접두어(expenses/{userId}/...)로 먼저 확인하고,
     * S3 HeadObject로 실제 업로드까지 확인한다. 통과하지 못하면 예외를 던지고, 통과하면 그냥 반환한다 —
     * URL은 더 이상 여기서 만들지 않는다(조회 시점에 presignGetUrl()로 별도 발급).
     * ExpenseService.create()가 imageKey를 받을 때도 그대로 호출한다.
     */
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
     * GET /expenses/{expenseId} 응답에 실을 이미지 조회 URL을 그때그때 새로 서명해서 만든다.
     * ExpenseDetail.imageKey는 DB에 저장돼 있지만 imageUrl은 저장하지 않으므로, 조회할 때마다 호출부
     * (ExpenseService.getDetail())가 이 메서드로 새 URL을 받아 응답에 채워 넣는다.
     * 여기서 소유권을 다시 검증하지 않는 이유: imageKey는 이미 owner의 ExpenseDetail 행에서 나온 값이고,
     * 호출부가 loadOwned()로 지출 자체의 소유권을 이미 확인한 뒤에만 이 메서드를 호출하기 때문.
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

    /** 옛 이미지 정리 실패로 본 요청(교체/삭제)까지 실패시키지 않기 위해 예외를 삼키고 경고 로그만 남긴다. */
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
