package Hampouch.server.domain.user.dto.request;

import Hampouch.server.domain.user.entity.NotificationDay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

/**
 * PUT은 전체 교체 방식 - 요청에 실린 필드 전체가 저장된 전체 설정을 덮어쓴다.
 * enabled류 필드는 primitive boolean이 아니라 Boolean + @NotNull이다 - primitive였다면 필드가
 * 요청에서 통째로 빠져도 Jackson이 기본값 false로 채워서 검증 오류 없이 조용히 꺼짐으로 처리됐다
 * (PR #229 리뷰 지적).
 */
public record NotificationScheduleUpdateRequest(
        @NotNull(message = "챌린지 알림 여부는 필수입니다.")
        Boolean challengeAlert,

        @NotNull(message = "햄배틀 알림 여부는 필수입니다.")
        Boolean battleAlert,

        @NotNull(message = "커뮤니티 알림 여부는 필수입니다.")
        Boolean communityAlert,

        @NotNull(message = "기록 알림 설정은 필수입니다.")
        @Valid
        RecordAlert recordAlert
) {
    public record RecordAlert(
            @NotNull(message = "기록 알림 여부는 필수입니다.")
            Boolean enabled,

            @NotNull(message = "미입력 알림 설정은 필수입니다.")
            @Valid
            MissingInput missingInput,

            @NotNull(message = "한도 초과 알림 설정은 필수입니다.")
            @Valid
            LimitExceeded limitExceeded
    ) {
    }

    public record MissingInput(
            @NotNull(message = "미입력 알림 여부는 필수입니다.")
            Boolean enabled,

            @NotEmpty(message = "요일을 1개 이상 선택해주세요.")
            Set<NotificationDay> days,

            @NotBlank(message = "시각은 HH:mm 형식으로 입력해주세요.")
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시각은 HH:mm 형식으로 입력해주세요.")
            String time
    ) {
    }

    public record LimitExceeded(
            @NotNull(message = "한도 초과 알림 여부는 필수입니다.")
            Boolean enabled
    ) {
    }
}
