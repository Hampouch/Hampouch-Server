package Hampouch.server.domain.expense.service;

/**
 * Challenge 도메인이 일별 예산 초과 여부를 판단할 때 쓰는 조회 결과
 * Challenge domain에서 그 날의 Challenge 성공 / 실패 여부 확인을 위한 DTO
 * hasRecord: 그날 기록 자체가 없음과 기록의 합계가 0원을 구분
 * hasRecord는 금액과 무관하게 해당 날짜에 ACTIVE 행이 존재하는지만 본다.
 */
public record DaySpending(
        int totalAmount,
        boolean hasRecord
) {
}
