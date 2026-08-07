package Hampouch.server.domain.rest.dto;

/**
 * 복귀 팝업의 세 선택지(명세 §2) — POST /api/rests/resume 요청의 when 값.
 * 세 값은 "오늘/내일/모레+"라는 날짜 눈금이 아니라 "답변 둘 + 보류 하나"다:
 * - NOW: 복귀 결정 — 지금 바로(오늘로 휴식 종료). 클라는 챌린지 생성 화면으로 이동.
 * - TOMORROW: 복귀 결정 — 내일부터(오늘은 휴식기 홈 유지).
 * - EXTEND: 결정 보류 — 복귀일을 잡는 게 아니라 다음에 물어볼 날(예정일)만 extendDays만큼 미룬다(필수).
 *   새 예정일이 오면 팝업이 다시 뜨고, 연장 중에도 조기 복귀(새 챌린지 생성)는 언제든 가능하다.
 * 목록에 없는 문자열이 오면 요청 본문 역직렬화가 실패해 공통 처리(GlobalExceptionHandler)가 400으로 응답한다.
 */
public enum ResumeWhen {
    NOW,
    TOMORROW,
    EXTEND
}
