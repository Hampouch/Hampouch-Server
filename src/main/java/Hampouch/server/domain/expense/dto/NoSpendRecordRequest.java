package Hampouch.server.domain.expense.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;

public record NoSpendRecordRequest(
        @NotNull
        @PastOrPresent
        LocalDate date
) {
}
