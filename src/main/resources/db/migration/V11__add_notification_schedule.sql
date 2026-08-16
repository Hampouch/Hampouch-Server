CREATE TABLE notification_schedule (
    user_id BIGINT NOT NULL,
    challenge_alert BIT NOT NULL,
    battle_alert BIT NOT NULL,
    community_alert BIT NOT NULL,
    record_alert_enabled BIT NOT NULL,
    missing_input_enabled BIT NOT NULL,
    missing_input_time TIME NOT NULL,
    limit_exceeded_enabled BIT NOT NULL,
    CONSTRAINT pk_notification_schedule PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_schedule_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE notification_schedule_missing_input_day (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    day_of_week ENUM ('FRI', 'MON', 'SAT', 'SUN', 'THU', 'TUE', 'WED') NOT NULL,
    CONSTRAINT pk_notification_schedule_missing_input_day PRIMARY KEY (id),
    CONSTRAINT uq_notification_missing_input_day UNIQUE (user_id, day_of_week),
    CONSTRAINT fk_notification_missing_input_day_schedule FOREIGN KEY (user_id) REFERENCES notification_schedule (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 이 마이그레이션 이전에 이미 가입한 유저도 GET에서 404 없이 조회되도록
INSERT INTO notification_schedule (
    user_id, challenge_alert, battle_alert, community_alert,
    record_alert_enabled, missing_input_enabled, missing_input_time, limit_exceeded_enabled
)
SELECT user_id, 1, 1, 1, 1, 1, '21:00:00', 1
FROM users;

INSERT INTO notification_schedule_missing_input_day (user_id, day_of_week)
SELECT u.user_id, d.day_of_week
FROM users u
CROSS JOIN (
    SELECT 'MON' AS day_of_week UNION ALL SELECT 'TUE' UNION ALL SELECT 'WED' UNION ALL
    SELECT 'THU' UNION ALL SELECT 'FRI' UNION ALL SELECT 'SAT' UNION ALL SELECT 'SUN'
) d;
