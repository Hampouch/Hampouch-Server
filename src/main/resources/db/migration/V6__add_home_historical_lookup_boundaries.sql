-- 기존 조기 종료 건은 원 스키마에 비활성 시작일이 없어 값을 추정하지 않는다.
ALTER TABLE challenge
    ADD COLUMN inactive_from DATE NULL AFTER end_reason,
    ADD INDEX idx_challenge_user_date_lookup
        (user_id, start_date, id, end_date, inactive_from);

ALTER TABLE user_rest
    ADD INDEX idx_user_rest_user_date_lookup
        (user_id, rest_start_date, actual_resume_date);
