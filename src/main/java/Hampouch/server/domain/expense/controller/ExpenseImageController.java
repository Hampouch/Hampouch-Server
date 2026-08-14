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
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ExpenseImageController {

    private final ExpenseImageService expenseImageService;

    @PostMapping("/api/expenses/photos/presigned")
    public ResponseEntity<ApiResponse<ExpenseImagePresignResponse>> presign(
            @LoginUserId Long userId,
            @RequestParam(required = false) Long expenseId,
            @Valid @RequestBody ExpenseImagePresignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("업로드 URL이 발급되었습니다.", expenseImageService.presign(userId, expenseId, request)));    }

    @PatchMapping("/api/expenses/{expenseId}/photos")
    public ResponseEntity<ApiResponse<Void>> attach(
            @LoginUserId Long userId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseImageAttachRequest request) {
        expenseImageService.attach(userId, expenseId, request.imageKey());
        return ResponseEntity.ok(ApiResponse.success("사진이 변경되었습니다.", null));
    }

    @DeleteMapping("/api/expenses/{expenseId}/photos")
    public ResponseEntity<ApiResponse<Void>> remove(
            @LoginUserId Long userId,
            @PathVariable Long expenseId) {
        expenseImageService.remove(userId, expenseId);
        return ResponseEntity.ok(ApiResponse.success("사진이 삭제되었습니다.", null));
    }
}
