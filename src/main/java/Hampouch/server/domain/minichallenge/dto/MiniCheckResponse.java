package Hampouch.server.domain.minichallenge.dto;

import java.time.LocalDate;

/** PUT /api/mini-challenges/{id}/check 응답 (200 OK, 명세 §5). date = 실제 반영된 날짜(생략 요청이면 오늘). */
public record MiniCheckResponse(
        Long miniChallengeId,
        LocalDate date,
        boolean checked
) {
}
