package Hampouch.server.domain.user.dto.request;

import Hampouch.server.domain.user.entity.NotificationDay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

/** PUT은 전체 교체 방식 - 요청에 실린 필드 전체가 저장된 전체 설정을 덮어쓴다. */
public record NotificationScheduleUpdateRequest(
        boolean challengeAlert,
        boolean battleAlert,
        boolean communityAlert,

        @NotNull(message = "기록 알림 설정은 필수입니다.")
        @Valid
        RecordAlert recordAlert
) {
    public record RecordAlert(
            boolean enabled,

            @NotNull(message = "미입력 알림 설정은 필수입니다.")
            @Valid
            MissingInput missingInput,

            @NotNull(message = "한도 초과 알림 설정은 필수입니다.")
            @Valid
            LimitExceeded limitExceeded
    ) {
    }

    public record MissingInput(
            boolean enabled,

            @NotEmpty(message = "요일을 1개 이상 선택해주세요.")
            Set<NotificationDay> days,

            @NotBlank(message = "시각은 HH:mm 형식으로 입력해주세요.")
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시각은 HH:mm 형식으로 입력해주세요.")
            String time
    ) {
    }

    public record LimitExceeded(
            boolean enabled
    ) {
    }
}
