package Hampouch.server.domain.expense.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;

/** '오늘은 안 썼어요'를 기록할 날짜. */
public record NoSpendRecordRequest(
        @NotNull
        @PastOrPresent
        LocalDate date
) {
}
