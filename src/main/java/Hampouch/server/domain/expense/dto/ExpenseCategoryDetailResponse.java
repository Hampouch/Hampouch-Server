package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /expenses/analysis/category/{category} 응답 — 카테고리별 자세히 보기.
 * 상단 탭바에서 카테고리를 바꾸면 path(category)만 교체해 같은 기간으로 다시 호출
 * ratio: 지출액 기준 정수 퍼센트
 * 해당 기간에 그 카테고리 지출이 없으면 404가 아니라 200 + totalAmount 0 / count 0 / items 빈 배열
 */
public record ExpenseCategoryDetailResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        ExpenseCategory category,
        long totalAmount,
        int count,
        int ratio,
        List<ExpenseAnalysisItem> items
) {}
