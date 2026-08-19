package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * 고정 날짜 챌린지 시작을 위한 DTO, 기간·고정일·목표는 모두 직전 챌린지와 오늘로부터 서버가 계산.
 */
public record StartFixedDateChallengeRequest(
        @NotNull
        @Positive
        Long sourceChallengeId,

        /**
         * 클라이언트가 초안에서 받은 주기 시작일. 계산에는 쓰지 않고 초안이 아직 유효한지 확인만 한다.
         * 늦게 입장하면 주기 시작일이 과거이므로 @FutureOrPresent를 붙이면 안 된다.
         */
        @NotNull
        LocalDate startDate
) {
}
