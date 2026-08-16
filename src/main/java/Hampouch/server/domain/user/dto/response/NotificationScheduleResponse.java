package Hampouch.server.domain.user.dto.response;

import Hampouch.server.domain.user.entity.NotificationDay;
import Hampouch.server.domain.user.entity.NotificationSchedule;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;
import java.util.Set;

public record NotificationScheduleResponse(
        boolean challengeAlert,
        boolean battleAlert,
        boolean communityAlert,
        RecordAlert recordAlert
) {
    public record RecordAlert(
            boolean enabled,
            MissingInput missingInput,
            LimitExceeded limitExceeded
    ) {
    }

    public record MissingInput(
            boolean enabled,
            Set<NotificationDay> days,
            @JsonFormat(pattern = "HH:mm") LocalTime time
    ) {
    }

    public record LimitExceeded(
            boolean enabled
    ) {
    }

    public static NotificationScheduleResponse of(NotificationSchedule schedule) {
        return new NotificationScheduleResponse(
                schedule.isChallengeAlert(),
                schedule.isBattleAlert(),
                schedule.isCommunityAlert(),
                new RecordAlert(
                        schedule.isRecordAlertEnabled(),
                        new MissingInput(
                                schedule.isMissingInputEnabled(),
                                schedule.getMissingInputDays(),
                                schedule.getMissingInputTime()
                        ),
                        new LimitExceeded(schedule.isLimitExceededEnabled())
                )
        );
    }
}
