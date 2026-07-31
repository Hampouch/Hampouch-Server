package Hampouch.server.domain.expense.service;

/**
 * Challenge 도메인이 일별 예산 초과 여부를 판단할 때 쓰는 조회 결과
 * Challenge domain에서 그 날의 Challenge 성공 / 실패 여부 확인을 위한 DTO
 * hasRecord: 그날 기록 자체가 없음과 기록의 합계가 0원을 구분
 * 다만 현재 Expense 등록 API는 @Min(1), 현재는 @Min(0)으로 변경 예정이지만 향후 협의 필요
 * hasRecord는 그 결정과 무관하게 "해당 날짜에 ACTIVE 행이 존재하는가"만
 * 보므로, 나중에 0원 기록이 허용돼도 코드 변경 없이 정상 동작한다.
 */
public record DaySpending(
        int totalAmount,
        boolean hasRecord
) {
}
