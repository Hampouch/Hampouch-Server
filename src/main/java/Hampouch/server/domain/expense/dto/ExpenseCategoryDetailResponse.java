package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /expenses/analysis/category/{category} 응답 — 카테고리별 자세히 보기. ratio는 정수 퍼센트다.
 * 해당 기간에 그 카테고리 지출이 없으면 404가 아니라 0과 빈 배열로 응답한다.
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
