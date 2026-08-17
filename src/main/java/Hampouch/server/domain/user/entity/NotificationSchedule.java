package Hampouch.server.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마이페이지 알림 설정 1건. PK를 users.user_id와 공유해 유저당 정확히 1행을 강제한다(ExpenseDetail과 동일한 1:1 패턴).
 * User 쪽엔 역참조를 두지 않는다 - 더 자주 조회되는 User에 N+1 위험을 얹지 않기 위해 단방향 유지.
 * 미입력 알림 요일은 콤마문자열이 아니라 NotificationMissingInputDay 자식 엔티티(요일당 1행)로 정규화해서 저장한다.
 */
@Getter
@Entity
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_schedule")
public class NotificationSchedule {

    private static final LocalTime DEFAULT_MISSING_INPUT_TIME = LocalTime.of(21, 0);

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "challenge_alert", nullable = false)
    private boolean challengeAlert;

    @Column(name = "battle_alert", nullable = false)
    private boolean battleAlert;

    @Column(name = "community_alert", nullable = false)
    private boolean communityAlert;

    @Column(name = "record_alert_enabled", nullable = false)
    private boolean recordAlertEnabled;

    @Column(name = "missing_input_enabled", nullable = false)
    private boolean missingInputEnabled;

    // 원본 행 컬렉션은 외부에 노출하지 않는다 - 외부엔 getMissingInputDays()가 반환하는 Set<NotificationDay>만 공개.
    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "notificationSchedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NotificationMissingInputDay> missingInputDayRows = new ArrayList<>();

    @Column(name = "missing_input_time", nullable = false)
    private LocalTime missingInputTime;

    @Column(name = "limit_exceeded_enabled", nullable = false)
    private boolean limitExceededEnabled;

    private NotificationSchedule(
            User user,
            boolean challengeAlert,
            boolean battleAlert,
            boolean communityAlert,
            boolean recordAlertEnabled,
            boolean missingInputEnabled,
            Set<NotificationDay> missingInputDays,
            LocalTime missingInputTime,
            boolean limitExceededEnabled
    ) {
        this.user = user;
        this.challengeAlert = challengeAlert;
        this.battleAlert = battleAlert;
        this.communityAlert = communityAlert;
        this.recordAlertEnabled = recordAlertEnabled;
        this.missingInputEnabled = missingInputEnabled;
        replaceMissingInputDays(missingInputDays);
        this.missingInputTime = missingInputTime;
        this.limitExceededEnabled = limitExceededEnabled;
    }

    /** 가입 직후 기본값 - PM 확정 답변대로 전 알림 켜짐, 미입력 알림은 매일 21:00. */
    public static NotificationSchedule createDefault(User user) {
        return new NotificationSchedule(
                user, true, true, true, true, true,
                EnumSet.allOf(NotificationDay.class),
                DEFAULT_MISSING_INPUT_TIME,
                true
        );
    }

    /** PUT은 전체 교체 방식 - 요청에 실린 필드 전체로 덮어쓴다. */
    public void replaceWith(
            boolean challengeAlert,
            boolean battleAlert,
            boolean communityAlert,
            boolean recordAlertEnabled,
            boolean missingInputEnabled,
            Set<NotificationDay> missingInputDays,
            LocalTime missingInputTime,
            boolean limitExceededEnabled
    ) {
        this.challengeAlert = challengeAlert;
        this.battleAlert = battleAlert;
        this.communityAlert = communityAlert;
        this.recordAlertEnabled = recordAlertEnabled;
        this.missingInputEnabled = missingInputEnabled;
        replaceMissingInputDays(missingInputDays);
        this.missingInputTime = missingInputTime;
        this.limitExceededEnabled = limitExceededEnabled;
    }

    public Set<NotificationDay> getMissingInputDays() {
        return missingInputDayRows.stream()
                .map(NotificationMissingInputDay::getDay)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationDay.class)));
    }

    /**
     * clear() 후 통째로 재추가하면 안 된다 - Hibernate의 flush 순서는 새 INSERT를 orphanRemoval DELETE보다
     * 먼저 실행해서, 이전·이후 요일 집합이 하나라도 겹치면(흔한 경우) 아직 안 지워진 기존 행과 새로 넣는
     * 행이 같은 (user_id, day_of_week)로 충돌해 uq_notification_missing_input_day 위반이 난다
     * (H2 통합테스트에서 실제로 재현됨). 그래서 빠진 요일만 지우고 새로 생긴 요일만 추가하는 차이만
     * 반영한다 - 겹치는 요일 행은 아예 건드리지 않으므로 삭제·삽입이 같은 요일에서 충돌할 일이 없다.
     */
    private void replaceMissingInputDays(Set<NotificationDay> days) {
        this.missingInputDayRows.removeIf(row -> !days.contains(row.getDay()));

        Set<NotificationDay> remaining = getMissingInputDays();
        Arrays.stream(NotificationDay.values())
                .filter(days::contains)
                .filter(day -> !remaining.contains(day))
                .forEach(day -> this.missingInputDayRows.add(new NotificationMissingInputDay(this, day)));
    }
}
