ALTER TABLE challenge
    ADD INDEX idx_challenge_status_id (status, id);
