package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.NotificationScheduleUpdateRequest;
import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.entity.NotificationDay;
import Hampouch.server.domain.user.entity.NotificationSchedule;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.NotificationScheduleRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * 서비스 상태 전이·검증. 리포지토리는 Mockito 목 — DB 불필요
 */
@ExtendWith(MockitoExtension.class)
class NotificationScheduleServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    NotificationScheduleRepository notificationScheduleRepository;
    @Mock
    UserService userService;
    @Mock
    UserOperationLock userOperationLock;

    private NotificationScheduleService service() {
        return new NotificationScheduleService(notificationScheduleRepository, userService, userOperationLock);
    }

    // ---------- getSchedule ----------

    @Test
    @DisplayName("정상 조회면 저장된 알림 설정을 그대로 반환한다")
    void getSchedule_returnsStoredSchedule() {
        NotificationSchedule schedule = defaultSchedule(USER_ID);
        when(notificationScheduleRepository.findById(USER_ID)).thenReturn(Optional.of(schedule));

        NotificationScheduleResponse response = service().getSchedule(USER_ID);

        assertThat(response.challengeAlert()).isTrue();
        assertThat(response.recordAlert().missingInput().days())
                .containsExactlyElementsOf(EnumSet.allOf(NotificationDay.class));
        assertThat(response.recordAlert().missingInput().time()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던진다")
    void getSchedule_throws403WhenUserDeleted() {
        when(userService.getUser(USER_ID)).thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        assertThatThrownBy(() -> service().getSchedule(USER_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
    }

    @Test
    @DisplayName("가입은 됐지만 설정 행이 없으면(방어적 상황) 404(NOTIFICATION_SCHEDULE_NOT_FOUND)를 던진다")
    void getSchedule_throws404WhenScheduleRowMissing() {
        when(notificationScheduleRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getSchedule(USER_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.NOTIFICATION_SCHEDULE_NOT_FOUND);
    }

    // ---------- updateSchedule ----------

    @Test
    @DisplayName("정상 변경이면 전체 교체되어 바뀐 설정을 반환한다")
    void updateSchedule_returnsFullyReplacedSchedule() {
        NotificationSchedule schedule = defaultSchedule(USER_ID);
        when(notificationScheduleRepository.findById(USER_ID)).thenReturn(Optional.of(schedule));

        NotificationScheduleResponse response = service().updateSchedule(USER_ID, updateRequest(
                false, false, false, false, false, Set.of(NotificationDay.MON), "07:30", false));

        assertThat(response.challengeAlert()).isFalse();
        assertThat(response.battleAlert()).isFalse();
        assertThat(response.communityAlert()).isFalse();
        assertThat(response.recordAlert().enabled()).isFalse();
        assertThat(response.recordAlert().missingInput().enabled()).isFalse();
        assertThat(response.recordAlert().missingInput().days()).containsExactly(NotificationDay.MON);
        assertThat(response.recordAlert().missingInput().time()).isEqualTo(LocalTime.of(7, 30));
        assertThat(response.recordAlert().limitExceeded().enabled()).isFalse();
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403(USER_DELETED)을 던진다")
    void updateSchedule_throws403WhenUserDeleted() {
        when(userService.getUser(USER_ID)).thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        assertThatThrownBy(() -> service().updateSchedule(USER_ID, updateRequest(
                true, true, true, true, true, Set.of(NotificationDay.MON), "21:00", true)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_DELETED);
    }

    @Test
    @DisplayName("getUser()로 조회하기 전에 UserOperationLock으로 사용자 행을 먼저 잠근다 " +
            "- 겹치는 요일로 동시에 들어오는 PUT 두 개를 직렬화해 uq_notification_missing_input_day 경쟁을 막기 위함")
    void updateSchedule_locksUserBeforeReadingSchedule() {
        NotificationSchedule schedule = defaultSchedule(USER_ID);
        when(notificationScheduleRepository.findById(USER_ID)).thenReturn(Optional.of(schedule));

        service().updateSchedule(USER_ID, updateRequest(
                true, true, true, true, true, Set.of(NotificationDay.MON), "21:00", true));

        InOrder order = inOrder(userOperationLock, notificationScheduleRepository);
        order.verify(userOperationLock).lock(USER_ID);
        order.verify(notificationScheduleRepository).findById(USER_ID);
    }

    @Test
    @DisplayName("가입은 됐지만 설정 행이 없으면(방어적 상황) 404(NOTIFICATION_SCHEDULE_NOT_FOUND)를 던진다")
    void updateSchedule_throws404WhenScheduleRowMissing() {
        when(notificationScheduleRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateSchedule(USER_ID, updateRequest(
                true, true, true, true, true, Set.of(NotificationDay.MON), "21:00", true)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.NOTIFICATION_SCHEDULE_NOT_FOUND);
    }

    private static NotificationScheduleUpdateRequest updateRequest(
            boolean challengeAlert, boolean battleAlert, boolean communityAlert,
            boolean recordAlertEnabled, boolean missingInputEnabled,
            Set<NotificationDay> days, String time, boolean limitExceededEnabled
    ) {
        return new NotificationScheduleUpdateRequest(
                challengeAlert, battleAlert, communityAlert,
                new NotificationScheduleUpdateRequest.RecordAlert(
                        recordAlertEnabled,
                        new NotificationScheduleUpdateRequest.MissingInput(missingInputEnabled, days, time),
                        new NotificationScheduleUpdateRequest.LimitExceeded(limitExceededEnabled)
                )
        );
    }

    private static NotificationSchedule defaultSchedule(Long userId) {
        User user = User.createLocalUser("user@hampouch.com", "encoded", "닉네임");
        ReflectionTestUtils.setField(user, "id", userId);
        NotificationSchedule schedule = NotificationSchedule.createDefault(user);
        ReflectionTestUtils.setField(schedule, "userId", userId);
        return schedule;
    }
}
