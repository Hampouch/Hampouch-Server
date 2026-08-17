package Hampouch.server.domain.battle.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateBattleRequest(
        @NotBlank(message = "햄배틀 제목을 입력해주세요.")
        @Size(max = 100, message = "제목은 100자 이하로 입력해주세요.")
        String title,

        @NotNull(message = "참가 정원을 입력해주세요.")
        Integer capacity,

        @NotNull(message = "참가 기간을 선택해주세요.")
        Integer durationDays,

        @NotNull(message = "시작일을 입력해주세요.")
        LocalDate startDate,

        @NotBlank(message = "벌칙을 입력해주세요.")
        @Size(max = 100, message = "벌칙은 100자 이하로 입력해주세요.")
        String penalty
) {
}
