package Hampouch.server.domain.user;

import Hampouch.server.domain.auth.dto.request.SignupRequest;
import Hampouch.server.domain.auth.dto.response.SignupResponse;
import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.service.AuthService;
import Hampouch.server.domain.user.dto.request.NotificationScheduleUpdateRequest;
import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.entity.NotificationDay;
import Hampouch.server.domain.user.entity.NotificationSchedule;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.NotificationScheduleRepository;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.NotificationScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 test DB에 실제로 커밋되는지 검증 — 테스트 레벨 @Transactional을 일부러 안 붙인다(롤백되면
 * "커밋됐다"는 걸 증명 못 함, BattleTransactionIntegrationTest와 동일 원칙).
 * #181: 회원가입 시 알림 설정 기본값 생성, PUT 전체교체가 자식 테이블(미입력 요일)까지 정확히
 * 반영되는지, 유저 간 격리가 되는지를 서비스 호출 뒤 별도 조회로 재확인한다.
 */
@SpringBootTest
class NotificationScheduleTransactionIntegrationTest {

    @Autowired
    AuthService authService;
    @Autowired
    NotificationScheduleService notificationScheduleService;
    @Autowired
    NotificationScheduleRepository notificationScheduleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @Test
    @DisplayName("회원가입하면 PM이 확정한 기본값(전체 알림 켜짐, 미입력 알림 매일 21:00)이 함께 커밋된다")
    void signup_commitsDefaultNotificationSchedule() {
        String email = "notification-default-" + System.currentTimeMillis() + "@hampouch.test";
        prepareVerifiedEmail(email);

        SignupResponse signupResponse = authService.signup(
                new SignupRequest(email, "password1!", "기본값유저"));

        // 서비스의 실제 조회 경로(트랜잭션 안에서 lazy 컬렉션까지 읽어 DTO로 변환)로 재조회 —
        // 엔티티를 트랜잭션 밖에서 직접 만지면 missingInputDayRows가 LazyInitializationException을 던진다.
        NotificationScheduleResponse schedule = notificationScheduleService.getSchedule(signupResponse.userId());
        assertThat(schedule.challengeAlert()).isTrue();
        assertThat(schedule.battleAlert()).isTrue();
        assertThat(schedule.communityAlert()).isTrue();
        assertThat(schedule.recordAlert().enabled()).isTrue();
        assertThat(schedule.recordAlert().missingInput().enabled()).isTrue();
        assertThat(schedule.recordAlert().missingInput().days()).isEqualTo(EnumSet.allOf(NotificationDay.class));
        assertThat(schedule.recordAlert().missingInput().time()).isEqualTo(LocalTime.of(21, 0));
        assertThat(schedule.recordAlert().limitExceeded().enabled()).isTrue();
    }

    @Test
    @DisplayName("PUT으로 전체 교체하면 이전 미입력 요일 행은 사라지고 새 요일만 남아 커밋된다")
    void updateSchedule_replacesMissingInputDayRowsAndCommits() {
        Long userId = newUserWithDefaultSchedule("notification-replace");

        notificationScheduleService.updateSchedule(userId, updateRequest(
                false, false, true, true, false,
                Set.of(NotificationDay.WED, NotificationDay.THU), "08:15", false));

        // 서비스 호출이 끝난 뒤 별도 조회(1차 캐시가 아니라 재조회)로도 반영돼 있어야 진짜 커밋된 것
        NotificationScheduleResponse reloaded = notificationScheduleService.getSchedule(userId);
        assertThat(reloaded.challengeAlert()).isFalse();
        assertThat(reloaded.battleAlert()).isFalse();
        assertThat(reloaded.communityAlert()).isTrue();
        assertThat(reloaded.recordAlert().missingInput().enabled()).isFalse();
        assertThat(reloaded.recordAlert().missingInput().days())
                .as("기본값이던 MON~SUN 7일 중 WED·THU만 남고 나머지는 지워져야 한다")
                .containsExactlyInAnyOrder(NotificationDay.WED, NotificationDay.THU);
        assertThat(reloaded.recordAlert().missingInput().time()).isEqualTo(LocalTime.of(8, 15));
        assertThat(reloaded.recordAlert().limitExceeded().enabled()).isFalse();
    }

    @Test
    @DisplayName("한 유저의 알림 설정 변경은 다른 유저의 설정에 영향을 주지 않는다")
    void updateSchedule_isIsolatedPerUser() {
        Long userAId = newUserWithDefaultSchedule("notification-isolation-a");
        Long userBId = newUserWithDefaultSchedule("notification-isolation-b");

        notificationScheduleService.updateSchedule(userAId, updateRequest(
                false, false, false, false, false, Set.of(NotificationDay.MON), "06:00", false));

        NotificationScheduleResponse userBSchedule = notificationScheduleService.getSchedule(userBId);
        assertThat(userBSchedule.challengeAlert()).as("A의 변경이 B에 새어나가면 안 된다").isTrue();
        assertThat(userBSchedule.recordAlert().missingInput().days()).isEqualTo(EnumSet.allOf(NotificationDay.class));
        assertThat(userBSchedule.recordAlert().missingInput().time()).isEqualTo(LocalTime.of(21, 0));
    }

    private Long newUserWithDefaultSchedule(String scenario) {
        User user = userRepository.save(User.createLocalUser(
                scenario + "-" + System.nanoTime() + "@hampouch.test", "encoded", "테스트유저" + System.nanoTime() % 100000));
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

    private void prepareVerifiedEmail(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }
}
