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
 * 지출 분석 4종(메인 / 카테고리별 자세히 보기 / 이유별 자세히 보기 / 월별 추이).
 *
 * ExpenseController와 클래스를 나눈 이유는 두 가지다. 의존하는 서비스가 ExpenseAnalysisService 하나로 다르고,
 * 기록 CRUD와 분석 화면은 서로 다른 속도로 바뀐다. 경로는 같은 뿌리를 쓰되(BASE_PATH 재사용) 파일만 갈라 두면
 * 한쪽을 손볼 때 다른 쪽이 변경 목록에 딸려 오지 않는다.
 *
 * 경로 주의: /api/expenses/analysis는 ExpenseController의 /api/expenses/{expenseId}와 같은 자리를 두고 겹친다.
 * 스프링은 변수 조각보다 글자 그대로인 조각을 먼저 고르므로 analysis 쪽이 이기지만, 이건 우리 코드가 아니라
 * 프레임워크 규칙에 기대는 부분이라 ExpenseAnalysisControllerTest에서 실제 라우팅으로 한 번 못 박아 둔다.
 *
 * 유저 식별은 ExpenseController와 동일하게 @LoginUserId로 주입받는다 —
 * 분석 대상은 언제나 로그인한 본인이고, 남의 기간을 조회할 경로는 열지 않는다.
 */
@RestController
@RequestMapping(ExpenseController.BASE_PATH + "/analysis")
@RequiredArgsConstructor
public class ExpenseAnalysisController {

    private final ExpenseAnalysisService expenseAnalysisService;

    /**
     * GET /api/expenses/analysis?periodStart=2026-05-01&periodEnd=2026-05-31 — 분석 메인.
     *
     * 두 날짜 모두 필수다. 달력에서 들어오면 그 달의 1일과 말일이, 챌린지 결과에서 들어오면 챌린지의 시작일과
     * 종료일이 그대로 실려 온다 — 어느 쪽이든 화면이 이미 기간을 정한 뒤에 호출하므로 서버가 채워 넣을 기본값이 없다.
     * 기간 규칙(시작일 역전 / 미래 시작일 / 100일 상한)은 전부 서비스가 본다. 컨트롤러가 같은 검증을 한 벌 더 들고 있으면
     * 두 벌이 어긋나는 날 어느 쪽이 진짜인지 알 수 없게 된다.
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
     *
     * 상단 탭바에서 카테고리만 바꿔 가며 같은 기간을 다시 부르는 화면이라 카테고리를 경로에 둔다.
     * 없는 이름이 오면 스프링이 변환에 실패하고, 그 예외는 GlobalExceptionHandler가 400으로 바꾼다.
     * 커스텀 카테고리는 별도 값이 아니라 ETC 한 덩어리로 조회된다.
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
     * GET /api/expenses/analysis/trend?month=2026-05 — 최근 6개월 월별 추이.
     *
     * month는 창의 마지막 달이고 개수는 서버 고정값(6)이다 — 막대 개수가 디자인에 박혀 있어 클라이언트가 정할 몫이 아니다.
     * 여기만 기간 대신 달 하나를 받으므로 periodStart/periodEnd와 섞이지 않게 파라미터 이름을 month로 따로 둔다.
     */
    @GetMapping("/trend")
    public ApiResponse<ExpenseTrendResponse> getTrend(
            @LoginUserId Long userId,
            @RequestParam YearMonth month) {
        return ApiResponse.success(expenseAnalysisService.getTrend(userId, month));
    }
}
