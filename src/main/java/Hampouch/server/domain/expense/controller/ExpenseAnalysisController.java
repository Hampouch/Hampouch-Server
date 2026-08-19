package Hampouch.server.domain.expense.controller;

import Hampouch.server.domain.expense.dto.ExpenseAnalysisResponse;
import Hampouch.server.domain.expense.dto.ExpenseCategoryDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseEmotionDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseTrendResponse;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseAnalysisService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 지출 분석 4종(메인 / 카테고리별 / 이유별 / 월별 추이). 경로 뿌리는 ExpenseController와 공유하되,
 * 기록 CRUD와 분석 화면이 서로 다른 속도로 바뀌므로 파일을 갈라 둔다.
 * /analysis는 /{expenseId}와 자리가 겹치는데 스프링이 글자 조각을 먼저 고르므로 이기고,
 * 프레임워크 규칙에 기대는 부분이라 ExpenseAnalysisControllerTest가 실제 라우팅으로 못 박는다.
 */
@RestController
@RequestMapping(ExpenseController.BASE_PATH + "/analysis")
@RequiredArgsConstructor
public class ExpenseAnalysisController {

    private final ExpenseAnalysisService expenseAnalysisService;

    /**
     * GET /api/expenses/analysis — 분석 메인. 두 날짜 모두 필수다.
     * 화면이 이미 기간을 정한 뒤 호출하므로 서버가 채워 넣을 기본값이 없다.
     * 기간 규칙(역전 / 미래 / 100일 상한)은 전부 서비스가 본다 — 검증이 두 벌이면 어긋나는 날 어느 쪽이 진짜인지 알 수 없다.
     */
    @GetMapping
    public ApiResponse<ExpenseAnalysisResponse> analyze(
            @LoginUserId Long userId,
            @RequestParam LocalDate periodStart,
            @RequestParam LocalDate periodEnd) {
        return ApiResponse.success(expenseAnalysisService.analyze(userId, periodStart, periodEnd));
    }

    /**
     * GET /api/expenses/analysis/category/{category} — 카테고리별 자세히 보기.
     * 탭바에서 카테고리만 바꿔 같은 기간을 다시 부르는 화면이라 카테고리를 경로에 둔다.
     * 없는 이름은 스프링 변환 실패 -> GlobalExceptionHandler가 400으로 바꾼다. 커스텀 카테고리는 ETC로 묶인다.
     */
    @GetMapping("/category/{category}")
    public ApiResponse<ExpenseCategoryDetailResponse> getCategoryDetail(
            @LoginUserId Long userId,
            @PathVariable ExpenseCategory category,
            @RequestParam LocalDate periodStart,
            @RequestParam LocalDate periodEnd) {
        return ApiResponse.success(
                expenseAnalysisService.getCategoryDetail(userId, category, periodStart, periodEnd));
    }

    /** GET /api/expenses/analysis/emotion/{emotion} — 이유별 자세히 보기. 카테고리별과 규칙이 같다. */
    @GetMapping("/emotion/{emotion}")
    public ApiResponse<ExpenseEmotionDetailResponse> getEmotionDetail(
            @LoginUserId Long userId,
            @PathVariable ExpenseEmotion emotion,
            @RequestParam LocalDate periodStart,
            @RequestParam LocalDate periodEnd) {
        return ApiResponse.success(
                expenseAnalysisService.getEmotionDetail(userId, emotion, periodStart, periodEnd));
    }

    /**
     * GET /api/expenses/analysis/trend — 최근 6개월 월별 추이. month는 창의 마지막 달이고 개수는 서버 고정값(6)이다.
     * 여기만 기간 대신 달 하나를 받으므로 파라미터 이름을 periodStart/periodEnd와 섞이지 않게 둔다.
     */
    @GetMapping("/trend")
    public ApiResponse<ExpenseTrendResponse> getTrend(
            @LoginUserId Long userId,
            @RequestParam YearMonth month) {
        return ApiResponse.success(expenseAnalysisService.getTrend(userId, month));
    }
}
