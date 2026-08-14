CREATE TABLE post (
    post_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_type ENUM ('FOOD_RECOMMEND', 'RECRUIT', 'TIP') NOT NULL,
    category ENUM ('COOKING', 'DISCOUNT', 'ETC', 'FOOD_RECOMMEND', 'GROCERY', 'RECORD', 'RECRUIT') NOT NULL,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    view_count INT NOT NULL,
    like_count INT NOT NULL,
    comment_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_post PRIMARY KEY (post_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- post_id는 AUTO_INCREMENT가 아니라 Post의 id를 그대로 공유하는 PK (엔티티에 @GeneratedValue 없음)
CREATE TABLE food_post_detail (
    post_id BIGINT NOT NULL,
    menu VARCHAR(100) NOT NULL,
    place VARCHAR(100) NOT NULL,
    price INT NOT NULL,
    taste_rating INT NOT NULL,
    cost_rating INT NOT NULL,
    mood_rating INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_food_post_detail PRIMARY KEY (post_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- post_id는 AUTO_INCREMENT가 아니라 Post의 id를 그대로 공유하는 PK (엔티티에 @GeneratedValue 없음)
CREATE TABLE recruit_post_detail (
    post_id BIGINT NOT NULL,
    battle_id BIGINT NOT NULL,
    battle_url VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_recruit_post_detail PRIMARY KEY (post_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE post_image (
    post_image_id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    image_key VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_post_image PRIMARY KEY (post_image_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE post_like (
    like_id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_post_like PRIMARY KEY (like_id),
    CONSTRAINT uk_post_like_post_user UNIQUE (post_id, user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE post_bookmark (
    bookmark_id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_post_bookmark PRIMARY KEY (bookmark_id),
    CONSTRAINT uk_post_bookmark_post_user UNIQUE (post_id, user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE post_comment (
    comment_id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_comment_id BIGINT NULL,
    content TEXT NOT NULL,
    is_deleted BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_post_comment PRIMARY KEY (comment_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;