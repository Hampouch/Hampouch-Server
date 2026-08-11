CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NULL,
    nickname VARCHAR(30) NULL,
    profile_image_url VARCHAR(500) NULL,
    provider ENUM ('LOCAL', 'GOOGLE', 'KAKAO') NOT NULL,
    provider_id VARCHAR(255) NULL,
    role ENUM ('USER', 'ADMIN') NOT NULL,
    status ENUM ('ACTIVE', 'DELETED') NOT NULL,
    last_updated DATE NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_nickname UNIQUE (nickname),
    CONSTRAINT uk_users_provider_provider_id UNIQUE (provider, provider_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE email_verifications (
    verification_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL,
    verification_code VARCHAR(10) NOT NULL,
    purpose ENUM ('SIGNUP', 'PASSWORD_RESET') NOT NULL,
    verified BIT NOT NULL,
    attempt_count INT NOT NULL,
    verified_at DATETIME(6) NULL,
    expired_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_email_verifications PRIMARY KEY (verification_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE refresh_tokens (
    token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expired_at DATETIME(6) NOT NULL,
    revoked BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (token_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE expense (
    expense_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(90) NULL,
    price INT NOT NULL,
    category ENUM (
        'DELIVERY',
        'DINING_OUT',
        'CONVENIENCE_STORE',
        'CAFE',
        'GROCERY',
        'DESSERT',
        'DRINKING',
        'ETC'
    ) NOT NULL,
    emotion ENUM ('STRESS', 'COMPENSATION', 'CONVENIENCE', 'IMPULSE', 'ETC') NOT NULL,
    status ENUM ('ACTIVE', 'DELETED') NOT NULL,
    expense_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    custom_category VARCHAR(50) NULL,
    custom_emotion VARCHAR(50) NULL,
    CONSTRAINT pk_expense PRIMARY KEY (expense_id),
    CONSTRAINT fk_expense_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    INDEX idx_expense_user_date_status (user_id, expense_date, status)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE expense_detail (
    expense_id BIGINT NOT NULL,
    memo VARCHAR(300) NULL,
    image_key VARCHAR(500) NULL,
    CONSTRAINT pk_expense_detail PRIMARY KEY (expense_id),
    CONSTRAINT fk_expense_detail_expense FOREIGN KEY (expense_id) REFERENCES expense (expense_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE no_spend_day (
    no_spend_day_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_no_spend_day PRIMARY KEY (no_spend_day_id),
    CONSTRAINT uq_no_spend_day_user_date UNIQUE (user_id, record_date),
    CONSTRAINT fk_no_spend_day_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE battle (
    battle_id BIGINT NOT NULL AUTO_INCREMENT,
    battle_code VARCHAR(30) NOT NULL,
    title VARCHAR(100) NOT NULL,
    party_capacity INT NOT NULL,
    duration INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    penalty VARCHAR(100) NOT NULL,
    status ENUM ('READY', 'ONGOING', 'TERMINATED', 'CANCELLED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    creator_id BIGINT NOT NULL,
    penalty_user_id BIGINT NULL,
    CONSTRAINT pk_battle PRIMARY KEY (battle_id),
    CONSTRAINT uq_battle_code UNIQUE (battle_code),
    CONSTRAINT fk_battle_creator FOREIGN KEY (creator_id) REFERENCES users (user_id),
    CONSTRAINT fk_battle_penalty_user FOREIGN KEY (penalty_user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE battle_participant (
    participant_id BIGINT NOT NULL AUTO_INCREMENT,
    joined_at DATETIME(6) NOT NULL,
    is_valid BIT NOT NULL,
    final_rank INT NULL,
    total_amount INT NULL,
    user_id BIGINT NOT NULL,
    battle_id BIGINT NOT NULL,
    CONSTRAINT pk_battle_participant PRIMARY KEY (participant_id),
    CONSTRAINT uq_battle_participant UNIQUE (battle_id, user_id),
    CONSTRAINT fk_battle_participant_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_battle_participant_battle FOREIGN KEY (battle_id) REFERENCES battle (battle_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE challenge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    duration_days INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    budget_total INT NOT NULL,
    daily_limit INT NOT NULL,
    reset_by_payday BIT NOT NULL,
    payday_day INT NULL,
    status ENUM ('IN_PROGRESS', 'SUCCESS', 'FAIL', 'VOID') NOT NULL,
    active_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'IN_PROGRESS' THEN user_id END
    ) VIRTUAL,
    end_reason ENUM ('GIVEN_UP', 'MISSING_DAILY_INPUT') NULL,
    closed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_challenge PRIMARY KEY (id),
    CONSTRAINT uq_challenge_active_user UNIQUE (active_user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE challenge_weak_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    challenge_id BIGINT NOT NULL,
    category VARCHAR(50) NOT NULL,
    CONSTRAINT pk_challenge_weak_category PRIMARY KEY (id),
    CONSTRAINT uq_weak_category UNIQUE (challenge_id, category),
    CONSTRAINT fk_challenge_weak_category_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE challenge_day (
    id BIGINT NOT NULL AUTO_INCREMENT,
    challenge_id BIGINT NOT NULL,
    day_date DATE NOT NULL,
    spent_amount INT NOT NULL,
    status ENUM ('SUCCESS', 'OVER') NOT NULL,
    daily_limit INT NOT NULL,
    CONSTRAINT pk_challenge_day PRIMARY KEY (id),
    CONSTRAINT uq_challenge_day UNIQUE (challenge_id, day_date),
    CONSTRAINT fk_challenge_day_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE challenge_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    challenge_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    effective_date DATE NOT NULL,
    adjust_option ENUM ('PLUS_10', 'PLUS_20') NULL,
    previous_budget_total INT NOT NULL,
    new_budget_total INT NOT NULL,
    previous_daily_limit INT NOT NULL,
    new_daily_limit INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_challenge_adjustment PRIMARY KEY (id),
    CONSTRAINT uq_challenge_adjustment_seq UNIQUE (challenge_id, sequence_number),
    CONSTRAINT fk_challenge_adjustment_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE mini_challenge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    duration_days INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_mini_challenge PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE mini_challenge_day (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mini_challenge_id BIGINT NOT NULL,
    check_date DATE NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_mini_challenge_day PRIMARY KEY (id),
    CONSTRAINT uq_mini_challenge_day UNIQUE (mini_challenge_id, check_date),
    CONSTRAINT fk_mini_challenge_day_mini_challenge FOREIGN KEY (mini_challenge_id) REFERENCES mini_challenge (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE recommended_mini_challenge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    duration_days INT NOT NULL,
    CONSTRAINT pk_recommended_mini_challenge PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_rest (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    rest_start_date DATE NOT NULL,
    planned_resume_date DATE NOT NULL,
    actual_resume_date DATE NULL,
    unresumed_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN actual_resume_date IS NULL THEN user_id END
    ) VIRTUAL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_user_rest PRIMARY KEY (id),
    CONSTRAINT uq_user_rest_unresumed_user UNIQUE (unresumed_user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
