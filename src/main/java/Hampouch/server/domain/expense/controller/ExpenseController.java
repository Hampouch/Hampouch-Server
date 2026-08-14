package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;

@RestController
@RequestMapping(ExpenseController.BASE_PATH)
@RequiredArgsConstructor
public class ExpenseController {

    static final String BASE_PATH = "/api/expenses";

    private final ExpenseService expenseService;

    @PostMapping
    public ResponseEntity<ApiResponse<ExpenseCreateResponse>> create(
            @LoginUserId Long userId,
            @Valid @RequestBody ExpenseCreateRequest request) {
        ExpenseCreateResponse res = expenseService.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.expenseId()))
                .body(ApiResponse.success(res));
    }

    @PutMapping("/no-spend")
    public ResponseEntity<ApiResponse<Void>> recordNoSpend(
            @LoginUserId Long userId,
            @Valid @RequestBody NoSpendRecordRequest request) {
        expenseService.recordNoSpend(userId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/{expenseId}")
    public ApiResponse<ExpenseDetailResponse> getDetail(
            @LoginUserId Long userId,
            @PathVariable Long expenseId) {
        return ApiResponse.success(expenseService.getDetail(userId, expenseId));
    }

    @PutMapping("/{expenseId}")
    public ApiResponse<ExpenseCreateResponse> update(
            @LoginUserId Long userId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseUpdateRequest request) {
        return ApiResponse.success(expenseService.update(userId, expenseId, request));
    }

    //soft delete
    @DeleteMapping("/{expenseId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @LoginUserId Long userId,
            @PathVariable Long expenseId) {
        expenseService.delete(userId, expenseId);
        return ResponseEntity.ok(ApiResponse.success("지출 내역이 삭제되었습니다.", null));
    }

    @GetMapping("/day")
    public ApiResponse<ExpenseDayListResponse> getDayList(
            @LoginUserId Long userId,
            @RequestParam LocalDate date) {
        return ApiResponse.success(expenseService.getDayList(userId, date));
    }

    @GetMapping("/summary/week")
    public ApiResponse<ExpenseSummaryResponse> getWeekSummary(
            @LoginUserId Long userId,
            @RequestParam LocalDate standardDate) {
        return ApiResponse.success(expenseService.getWeekSummary(userId, standardDate));
    }

    @GetMapping("/summary/month")
    public ApiResponse<ExpenseSummaryResponse> getMonthSummary(
            @LoginUserId Long userId,
            @RequestParam YearMonth standardMonth) {
        return ApiResponse.success(expenseService.getMonthSummary(userId, standardMonth));
    }
}
