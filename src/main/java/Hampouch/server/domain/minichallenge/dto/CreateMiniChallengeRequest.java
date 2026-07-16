package Hampouch.server.domain.minichallenge.dto;

/**
 * POST /api/mini-challenges 요청 — 둘 중 한 형태만(명세 §3 XOR):
 * 추천에서 추가 = recommendedId만, 커스텀 생성 = custom만.
 * (§3 = 미니 챌린지 API 명세의 3절 "미니 추가" · XOR = 배타적 논리합,
 * "둘 중 정확히 하나만" — 둘 다 채우거나 둘 다 비우면 형태 위반 400 MINI_INVALID_BODY.)
 *
 * 필드가 둘이라고 둘 다 채워 오는 게 아니다 — 안 쓰는 쪽은 null로 비어 온다. 자바엔 "둘 중 하나"를 나타내는
 * 타입이 없어서 칸을 둘 다 두고 한쪽을 비우는 수밖에 없다(sealed interface로 흉내 낼 수는 있으나, 명세 JSON에
 * 어느 쪽인지 알리는 표식이 없고 필드 존재 자체가 구분자라 역직렬화 설정이 붙는다 — 2지선다엔 과하다).
 * 그래서 XOR을 타입이 못 막고 서비스가 막는다(recommendedId != null 과 custom != null 이 같으면 형태 위반).
 *
 * 일부러 jakarta validation 애너테이션을 안 붙였다 — XOR·비공백·기간 화이트리스트 위반에
 * 명세가 지정한 전용 에러 코드(MINI_INVALID_BODY / MINI_INVALID_DURATION)를 내려야 하는데,
 * 빈 검증 실패는 나연 공통 핸들러에서 일괄 VALIDATION_ERROR 코드로 떨어져 명세 코드와 어긋난다.
 * 그래서 이 바디의 검증은 MiniChallengeService가 담당(코드 지정 CustomException).
 */
public record CreateMiniChallengeRequest(

        Long recommendedId,

        Custom custom
) {

    /** 커스텀 생성 폼 — "나만의 챌린지 만들기" 화면의 입력값. */
    public record Custom(
            String title,
            Integer durationDays
    ) {
    }
}
