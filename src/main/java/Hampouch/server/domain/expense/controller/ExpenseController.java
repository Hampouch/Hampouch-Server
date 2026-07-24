package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseSummaryResponse;
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

/**
 * 지출 5개 우선순위 API(POST/GET/PUT/DELETE /expenses, GET /expenses/day).
 * 유저 식별은 @LoginUserId(SecurityContext의 인증된 principal, JwtFilter가 채워둠)로 주입받는다
 * AuthController.logout()/deleteMe()와 동일 패턴
 */
@RestController
@RequestMapping(ExpenseController.BASE_PATH)
@RequiredArgsConstructor
public class ExpenseController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로. */
    static final String BASE_PATH = "/api/expenses";

    private final ExpenseService expenseService;

    /** POST /api/expenses — 201 + Location 헤더(ChallengeController.create()와 동일 컨벤션). */
    @PostMapping
    public ResponseEntity<ApiResponse<ExpenseCreateResponse>> create(
            @LoginUserId Long userId,
            @Valid @RequestBody ExpenseCreateRequest request) {
        ExpenseCreateResponse res = expenseService.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.expenseId()))
                .body(ApiResponse.success(res));
    }

    /** GET /api/expenses/{expenseId} — 상세 조회. */
    @GetMapping("/{expenseId}")
    public ApiResponse<ExpenseDetailResponse> getDetail(
            @LoginUserId Long userId,
            @PathVariable Long expenseId) {
        return ApiResponse.success(expenseService.getDetail(userId, expenseId));
    }

    /**
     * PUT /api/expenses/{expenseId} — ExpenseCreateRequest/Response를 POST와 그대로 재사용
     * (두 DTO의 자체 Javadoc 참조). 200 OK, 별도 Location 없음.
     */
    @PutMapping("/{expenseId}")
    public ApiResponse<ExpenseCreateResponse> update(
            @LoginUserId Long userId,
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
            @LoginUserId Long userId,
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
            @LoginUserId Long userId,
            @RequestParam LocalDate date) {
        return ApiResponse.success(expenseService.getDayList(userId, date));
    }

    /**
     * GET /api/expenses/summary/week — stDate(?stDate=2026-06-05)가 속한 주(일~토)의 합계·일별 내역(이슈 #36).
     * getDayList()와 동일하게 화면 진입 자체가 날짜를 고르고 들어오는 흐름이라 stDate 생략 불가.
     */
    @GetMapping("/summary/week")
    public ApiResponse<ExpenseSummaryResponse> getWeekSummary(
            @LoginUserId Long userId,
            @RequestParam LocalDate stDate) {
        return ApiResponse.success(expenseService.getWeekSummary(userId, stDate));
    }

    /** GET /api/expenses/summary/month — stMonth(?stMonth=2026-06) 해당 월의 합계·일별 내역(이슈 #36). */
    @GetMapping("/summary/month")
    public ApiResponse<ExpenseSummaryResponse> getMonthSummary(
            @LoginUserId Long userId,
            @RequestParam YearMonth stMonth) {
        return ApiResponse.success(expenseService.getMonthSummary(userId, stMonth));
    }
}
