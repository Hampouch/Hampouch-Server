package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.NotificationScheduleUpdateRequest;
import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.entity.NotificationSchedule;
import Hampouch.server.domain.user.repository.NotificationScheduleRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationScheduleService {

    private final NotificationScheduleRepository notificationScheduleRepository;
    private final UserService userService;
    private final UserOperationLock userOperationLock;

    public NotificationScheduleResponse getSchedule(Long userId) {
        userService.getUser(userId);
        return NotificationScheduleResponse.of(getScheduleOrThrow(userId));
    }

    @Transactional
    public NotificationScheduleResponse updateSchedule(Long userId, NotificationScheduleUpdateRequest request) {
        // missingInputDayRows는 clear()+재추가 방식이라, 같은 유저의 PUT 두 개가 겹치는 요일로
        // 동시에 들어오면 각자 자기 트랜잭션이 읽은 스냅샷 기준으로만 DELETE하고 INSERT하게 된다 -
        // 서로의 커밋을 모른 채 같은 (user_id, day)를 동시에 INSERT하면 uq_notification_missing_input_day에
        // 걸려 500이 날 수 있다. UserOperationLock으로 같은 유저의 PUT을 먼저 직렬화해 이 경쟁을 없앤다.
        userOperationLock.lock(userId);
        userService.getUser(userId);
        NotificationSchedule schedule = getScheduleOrThrow(userId);

        NotificationScheduleUpdateRequest.RecordAlert recordAlert = request.recordAlert();
        NotificationScheduleUpdateRequest.MissingInput missingInput = recordAlert.missingInput();

        schedule.replaceWith(
                request.challengeAlert(),
                request.battleAlert(),
                request.communityAlert(),
                recordAlert.enabled(),
                missingInput.enabled(),
                missingInput.days(),
                LocalTime.parse(missingInput.time()),
                recordAlert.limitExceeded().enabled()
        );

        return NotificationScheduleResponse.of(schedule);
    }

    private NotificationSchedule getScheduleOrThrow(Long userId) {
        return notificationScheduleRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOTIFICATION_SCHEDULE_NOT_FOUND));
    }
}
