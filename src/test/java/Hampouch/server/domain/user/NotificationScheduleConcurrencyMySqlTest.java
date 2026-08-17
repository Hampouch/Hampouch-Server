package Hampouch.server.domain.user;

import Hampouch.server.domain.user.dto.request.NotificationScheduleUpdateRequest;
import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.entity.NotificationDay;
import Hampouch.server.domain.user.entity.NotificationSchedule;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.NotificationScheduleRepository;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.NotificationScheduleService;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NotificationScheduleService.updateSchedule의 UserOperationLock과 uq_notification_missing_input_day가
 * 동시 요청에서 요일 행 경쟁·중복 저장을 막는지 검증(#181).
 *
 * NotificationSchedule.replaceMissingInputDays()는 이전·이후 요일 집합의 차이만 반영한다(겹치는
 * 요일 행은 건드리지 않음) - 그래서 레이스가 나려면 두 동시 PUT이 "현재 없는 같은 요일"을 새로
 * INSERT하려는 상황이어야 한다. 서로의 커밋을 모른 채 같은 (user_id, day_of_week)를 INSERT하면
 * uq_notification_missing_input_day에 걸려 500이 날 수 있다(BattleConcurrencyMySqlTest가
 * uq_battle_participant에서 검증한 것과 동일 계열의 레이스). NotificationScheduleService.updateSchedule은
 * UserOperationLock으로 같은 유저의 PUT을 먼저 직렬화해 이 경쟁을 없앤다 - 이 테스트는 그 직렬화가
 * 실제로 500 없이 동작하는지 확인한다.
 */
@MySqlContainerTest
class NotificationScheduleConcurrencyMySqlTest {

    @Autowired
    NotificationScheduleService notificationScheduleService;
    @Autowired
    NotificationScheduleRepository notificationScheduleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * 동시성 없이도 검증 가능한 전제조건: uq_notification_missing_input_day 제약이 실제로 DB에
     * 존재하는지를 직접 확인한다(BattleConcurrencyMySqlTest의 uniqueConstraintRejectsDuplicateParticipantRow와
     * 동일 원칙) - 이 제약을 지우면(마이그레이션 롤백 등) 이 테스트가 실패한다.
     */
    @Test
    @DisplayName("uq_notification_missing_input_day 제약이 동일 유저·동일 요일의 중복 저장을 막는다")
    void uniqueConstraintRejectsDuplicateMissingInputDayRow() {
        Long userId = newUserWithDefaultSchedule("unique-constraint");

        // 가입 기본값에 이미 MON이 들어있다 - 같은 (user_id, day_of_week)를 raw JDBC로 한 번 더 넣으면 막혀야 한다.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_schedule_missing_input_day (user_id, day_of_week) VALUES (?, ?)", userId, "MON"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 유저의 PUT 두 개가 현재 없는 같은 요일을 동시에 새로 추가하려 해도 UserOperationLock이 " +
            "직렬화해 500 없이 둘 다 성공하고, 최종 요일 행이 승자 요청 하나와 정확히 일치한다")
    void concurrentUpdateAddingSameNewDay_serializesWithoutConstraintViolation() throws Exception {
        Long userId = newUserWithDefaultSchedule("concurrent-update");
        // 겹치는 요일 행은 diff 로직이 아예 건드리지 않으므로, 레이스를 재현하려면 "현재 없는" 요일을
        // 두 요청이 동시에 새로 추가하려는 상황이 필요하다 - 먼저 MON 하나로 줄여 TUE가 없는 상태를 만든다.
        notificationScheduleService.updateSchedule(userId, updateRequest(
                true, true, true, true, true, Set.of(NotificationDay.MON), "21:00", true));

        Set<NotificationDay> daysA = Set.of(NotificationDay.MON, NotificationDay.TUE, NotificationDay.WED);
        Set<NotificationDay> daysB = Set.of(NotificationDay.MON, NotificationDay.TUE, NotificationDay.THU);
        NotificationScheduleUpdateRequest requestA = updateRequest(
                true, true, true, true, true, daysA, "07:00", true);
        NotificationScheduleUpdateRequest requestB = updateRequest(
                false, false, false, true, true, daysB, "22:30", false);

        List<Outcome<NotificationScheduleResponse>> outcomes = race(
                () -> notificationScheduleService.updateSchedule(userId, requestA),
                () -> notificationScheduleService.updateSchedule(userId, requestB));

        assertThat(outcomes)
                .as("UserOperationLock으로 직렬화되므로 uq_notification_missing_input_day 위반 없이 둘 다 성공해야 한다")
                .allSatisfy(outcome -> assertThat(outcome.succeeded())
                        .as(() -> "예외 발생: " + outcome.error())
                        .isTrue());

        // 서비스의 실제 조회 경로로 재확인 — 엔티티를 트랜잭션 밖에서 직접 만지면 missingInputDayRows가
        // LazyInitializationException을 던진다.
        NotificationScheduleResponse finalSchedule = notificationScheduleService.getSchedule(userId);
        Set<NotificationDay> finalDays = finalSchedule.recordAlert().missingInput().days();
        assertThat(finalDays)
                .as("최종 요일 집합은 두 요청 중 정확히 하나와 일치해야 한다 - 경쟁으로 섞이면 안 된다")
                .isIn(daysA, daysB);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_schedule_missing_input_day WHERE user_id = ?",
                Integer.class, userId);
        assertThat(rowCount)
                .as("승자 요청의 요일 개수만큼만 행이 남아야 한다 - 패자 요청의 잔여·중복 행이 있으면 안 된다")
                .isEqualTo(finalDays.size());
    }

    private <T> List<Outcome<T>> race(Callable<? extends T> first, Callable<? extends T> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<T>> firstFuture = executor.submit(() -> runAfterBarrier(barrier, first));
            Future<Outcome<T>> secondFuture = executor.submit(() -> runAfterBarrier(barrier, second));

            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** barrier.await()는 두 워커 모두 도착해야만 반환되므로, 이후 request.call()은 항상 동시에 출발한다. */
    private <T> Outcome<T> runAfterBarrier(CyclicBarrier barrier, Callable<? extends T> request) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Outcome<>(null, e);
        }
        return capture(request);
    }

    private <T> Outcome<T> capture(Callable<? extends T> request) {
        try {
            return new Outcome<>(request.call(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private Long newUserWithDefaultSchedule(String scenario) {
        User user = userRepository.save(User.createLocalUser(
                scenario + "-" + System.nanoTime() + "@hampouch.test", "encoded", "동시성" + System.nanoTime() % 100000));
        notificationScheduleRepository.save(NotificationSchedule.createDefault(user));
        return user.getId();
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

    private record Outcome<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
