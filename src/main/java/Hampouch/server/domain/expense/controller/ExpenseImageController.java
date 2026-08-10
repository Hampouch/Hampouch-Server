package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseImageAttachRequest;
import Hampouch.server.domain.expense.dto.ExpenseImagePresignRequest;
import Hampouch.server.domain.expense.dto.ExpenseImagePresignResponse;
import Hampouch.server.domain.expense.service.ExpenseImageService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지출 이미지 업로드 3종(presign, 반영, 삭제)
 * ExpenseController와 분리한 이유: 이미지 업로드는 S3 연동이라 관심사가 다르고,
 * 커밋 단위/리뷰 단위를 지출 CRUD와 독립적으로 가져가기 위함.
 */
@RestController
@RequiredArgsConstructor
public class ExpenseImageController {

    private final ExpenseImageService expenseImageService;

    /**
     * POST /api/expenses/photos/presigned — presigned URL 발급. expenseId는 query param(선택)으로,
     * 지출 생성 전 업로드는 생략하고 기존 지출 이미지 교체 시에만 실어 보낸다.
     */
    @PostMapping("/api/expenses/photos/presigned")
    public ResponseEntity<ApiResponse<ExpenseImagePresignResponse>> presign(
            @LoginUserId Long userId,
            @RequestParam(required = false) Long expenseId,
            @Valid @RequestBody ExpenseImagePresignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("업로드 URL이 발급되었습니다.", expenseImageService.presign(userId, expenseId, request)));    }

    /** PATCH /api/expenses/{expenseId}/photos — 업로드 완료된 이미지를 지출 상세에 반영. */
    @PatchMapping("/api/expenses/{expenseId}/photos")
    public ResponseEntity<ApiResponse<Void>> attach(
            @LoginUserId Long userId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseImageAttachRequest request) {
        expenseImageService.attach(userId, expenseId, request.imageKey());
        return ResponseEntity.ok(ApiResponse.success("사진이 변경되었습니다.", null));
    }

    /** DELETE /api/expenses/{expenseId}/photos — 첨부된 이미지 제거(메모는 유지). */
    @DeleteMapping("/api/expenses/{expenseId}/photos")
    public ResponseEntity<ApiResponse<Void>> remove(
            @LoginUserId Long userId,
            @PathVariable Long expenseId) {
        expenseImageService.remove(userId, expenseId);
        return ResponseEntity.ok(ApiResponse.success("사진이 삭제되었습니다.", null));
    }
}
