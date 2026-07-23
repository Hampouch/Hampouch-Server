package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;

/**
 * 지출 5개 우선순위 API(POST/GET/PUT/DELETE /expenses, GET /expenses/day).
 *
 * TODO(로그인 연동): 유저 식별은 ChallengeController와 동일하게 연동 전까지 X-User-Id 헤더 스텁(기본 1) —
 * 연동 시 JWT sub 클레임(@AuthenticationPrincipal)으로 교체.
 */
@RestController
@RequestMapping(ExpenseController.BASE_PATH)
@RequiredArgsConstructor
public class ExpenseController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로. */
    static final String BASE_PATH = "/api/expenses";

    private static final String USER_HEADER = "X-User-Id";

    private final ExpenseService expenseService;

    /** POST /api/expenses — 201 + Location 헤더(ChallengeController.create()와 동일 컨벤션). */
    @PostMapping
    public ResponseEntity<ApiResponse<ExpenseCreateResponse>> create(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @Valid @RequestBody ExpenseCreateRequest request) {
        ExpenseCreateResponse res = expenseService.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.expenseId()))
                .body(ApiResponse.success(res));
    }

    /** GET /api/expenses/{expenseId} — 상세 조회. */
    @GetMapping("/{expenseId}")
    public ApiResponse<ExpenseDetailResponse> getDetail(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long expenseId) {
        return ApiResponse.success(expenseService.getDetail(userId, expenseId));
    }

    /**
     * PUT /api/expenses/{expenseId} — ExpenseCreateRequest/Response를 POST와 그대로 재사용
     * (두 DTO의 자체 Javadoc 참조). 200 OK, 별도 Location 없음.
     */
    @PutMapping("/{expenseId}")
    public ApiResponse<ExpenseCreateResponse> update(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseCreateRequest request) {
        return ApiResponse.success(expenseService.update(userId, expenseId, request));
    }

    /**
     * DELETE /api/expenses/{expenseId} — 소프트 삭제(Expense.delete()). 반환 데이터가 없어
     * UserController.deleteMe()와 동일하게 메시지만 담은 ApiResponse<Void>로 응답.
     */
    @DeleteMapping("/{expenseId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long expenseId) {
        expenseService.delete(userId, expenseId);
        return ResponseEntity.ok(ApiResponse.success("지출 내역이 삭제되었습니다.", null));
    }

    /**
     * GET /api/expenses/day — 캘린더 일간 조회. date는 쿼리 파라미터(?date=2026-07-18),
     * 화면 진입 자체가 특정 날짜를 골라 들어오는 흐름이라 생략 불가(RecommendedMiniChallengeController의
     * durationDays처럼 선택적인 필터가 아님) — required 기본값(true) 그대로 사용.
     */
    @GetMapping("/day")
    public ApiResponse<ExpenseDayListResponse> getDayList(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @RequestParam LocalDate date) {
        return ApiResponse.success(expenseService.getDayList(userId, date));
    }
}
