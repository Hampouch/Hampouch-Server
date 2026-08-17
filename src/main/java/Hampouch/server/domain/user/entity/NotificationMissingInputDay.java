package Hampouch.server.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미입력 알림을 보낼 요일 1개. 저장 단위 = 알림 설정(NotificationSchedule)별.
 * uq_notification_missing_input_day = (user_id, day_of_week) 유니크 — 같은 유저가 같은 요일을 중복 저장하는 것을 DB가 최종 차단.
 * 컬럼명은 day가 아니라 day_of_week — H2(MySQL 호환 모드로 테스트에 씀)가 DAY를 예약어로 취급해
 * unquoted 컬럼명으로 쓰면 CREATE TABLE 자체가 조용히 실패한다(실 MySQL에서는 문제없지만 테스트 DB 호환을 위해 회피).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_schedule_missing_input_day", uniqueConstraints =
        @UniqueConstraint(name = "uq_notification_missing_input_day", columnNames = {"user_id", "day_of_week"}))
public class NotificationMissingInputDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) // 이 테이블에 생기는 FK 컬럼 이름 — notification_schedule.user_id를 참조
    private NotificationSchedule notificationSchedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private NotificationDay day;

    NotificationMissingInputDay(NotificationSchedule notificationSchedule, NotificationDay day) {
        this.notificationSchedule = notificationSchedule;
        this.day = day;
    }
}
