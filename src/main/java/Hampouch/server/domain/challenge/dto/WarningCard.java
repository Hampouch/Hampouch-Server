package Hampouch.server.domain.challenge.dto;

/**
 * 홈 경고 카드 코드 — 응답 warningCards 배열에 enum 이름 그대로 직렬화되는 API 응답 어휘.
 * 현재 구현된 값은 없으며, warningCards 필드는 GOAL_TOO_TIGHT 복원(#224)이 사용할 예정이라 유지한다.
 * 향후 각 카드는 공통 게이트(오늘 사용률 등) 없이 각자의 트리거로 발동한다.
 * dto 패키지에 있는 이유는 AlertLevel과 동일 — DB에 저장되지 않고 응답에만 실리는 어휘라서.
 */
public enum WarningCard {
}
