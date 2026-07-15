package Hampouch.server.domain.minichallenge.dto;

/**
 * POST /api/mini-challenges 요청 — 둘 중 한 형태만(명세 §3 XOR):
 * 추천에서 추가 = recommendedId만, 커스텀 생성 = custom만.
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
