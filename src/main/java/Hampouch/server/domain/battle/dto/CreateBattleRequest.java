package Hampouch.server.domain.battle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /battles 요청 — capacity/durationDays/startDate 검증은 서비스 계층에서 한다
 * (INVALID_CAPACITY_RANGE/INVALID_START_DATE 전용 BattleErrorCode를 내야 하므로
 * @Min / @Max는 GlobalExceptionHandler가 전부 VALIDATION_ERROR로 검증
 */
public record CreateBattleRequest(
        @NotBlank @Size(max = 100)
        String title,

        @NotNull
        Integer capacity,

        @NotNull
        Integer durationDays,

        @NotNull
        LocalDate startDate,

        @NotBlank @Size(max = 100)
        String penalty
) {
}
