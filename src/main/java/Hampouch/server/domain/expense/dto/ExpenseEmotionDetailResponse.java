package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseEmotion;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /expenses/analysis/emotion/{emotion} 응답 — 지출 이유별 자세히 보기.
 * 카테고리별 자세히 보기와 구조가 같고 식별 필드만 category -> emotion으로 바뀐다.
 */
public record ExpenseEmotionDetailResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        ExpenseEmotion emotion,
        long totalAmount,
        int count,
        int ratio,
        List<ExpenseAnalysisItem> items
) {}
