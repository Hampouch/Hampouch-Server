package Hampouch.server.domain.expense.service;

/**
 * Challenge 도메인이 일별 예산 초과 여부를 판단할 때 쓰는 조회 결과
 * Challenge domain에서 그 날의  성공 / 실패 여부 확인을 위한 DTO
 */
public record DaySpending(
        long totalAmount,
        boolean hasRecord
) {
}
