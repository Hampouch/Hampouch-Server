package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 고정 날짜 챌린지 시작을 위한 DTO, 기간·고정일·목표는 모두 직전 챌린지와 오늘로부터 서버가 계산.
 */
public record StartFixedDateChallengeRequest(
        @NotNull
        @Positive
        Long sourceChallengeId
) {
}
