package Hampouch.server.domain.challenge.dto;

/**
 * 홈 경고 카드 코드 — 응답 warningCards 배열에 enum 이름 그대로 직렬화되는 API 응답 어휘.
 * 현재 구현된 값은 없으며, warningCards 필드는 WEAK_CATEGORY_ALERT(#52)를 위해 유지한다.
 * dto 패키지에 있는 이유는 AlertLevel과 동일 — DB에 저장되지 않고 응답에만 실리는 어휘라서.
 */
public enum WarningCard {
    // TODO(#52, 령준 EXPENSE 연동 후): WEAK_CATEGORY_ALERT 상수 추가 — 기준은 확정(0714: 카테고리
    //  누적 지출이 전체 예산의 70% 이상, 문구 '주의' 일괄). 추가 시점에 카드 파라미터(카테고리·금액) 필요 여부를
    //  재평가할 것 — 필요하면 코드 배열 → 객체 배열로 계약 표현 재설계(안드 연동 전인 지금이 바꾸기 싼 시기).
}
