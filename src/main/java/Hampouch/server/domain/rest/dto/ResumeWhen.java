package Hampouch.server.domain.rest.dto;

/**
 * 복귀 팝업의 세 선택지(명세 §2) — POST /api/rests/resume 요청의 when 값.
 * - NOW: 지금 바로 복귀(오늘로 휴식 종료). 클라는 챌린지 생성 화면으로 이동.
 * - TOMORROW: 내일부터 복귀(오늘은 휴식기 홈 유지).
 * - EXTEND: 조금 더 쉬기(복귀 예정일 연장, extendDays 필수).
 * 목록에 없는 문자열이 오면 요청 본문 역직렬화가 실패해 공통 처리(GlobalExceptionHandler)가 400으로 응답한다.
 */
public enum ResumeWhen {
    NOW,
    TOMORROW,
    EXTEND
}
