-- =====================================================================
-- 팀 ERDCloud 전체 export 백업 (2026-07-06)
-- 시점: 일혁 영역 갈아엎기(Untitled4 삭제 + 신규 4개 import) **직전** 상태.
-- 출처: 일혁이 ERDCloud에서 export해 전달한 원문 그대로 (수정 없음).
-- 참고: Untitled=user(나연) · Untitled2=auth(나연) · Untitled3=refresh token(나연)
--       Untitled4=구 미니챌린지(분담 재정의 이전 구버전 — 교체 대상)
--       Untitled5=지출(령준) · Untitled6=지출 메모/사진(령준) · Untitled7=카테고리(령준)
-- =====================================================================

CREATE TABLE `challenge` (
	`id`	bigint	NOT NULL,
	`duration_days`	int	NOT NULL	COMMENT '7/14/30/N',
	`start_date`	date	NOT NULL,
	`end_date`	date	NOT NULL	COMMENT 'start + duration - 1',
	`budget_total`	int	NOT NULL	COMMENT '기간 목표(원)',
	`daily_limit`	int	NOT NULL	COMMENT '스냅샷 = budget_total / duration_days',
	`reset_by_payday`	tinyint(1)	NOT NULL	COMMENT '월급날 리셋',
	`payday_day`	int	NULL	COMMENT '1~31',
	`status`	varchar(20)	NOT NULL	COMMENT 'IN_PROGRESS/SUCCESS/FAIL',
	`created_at`	datetime(6)	NOT NULL,
	`user_id2`	BIGINT	NOT NULL
);

CREATE TABLE `Untitled5` (
	`Key`	BIGINT	NOT NULL,
	`Field10`	VARCHAR(50)	NOT NULL,
	`Field4`	datetime	NOT NULL,
	`Field`	INTEGER	NOT NULL,
	`Field3`	VARCHAR(30)	NOT NULL	COMMENT '스트레스/보상/귀찮아서/먹고 싶어서 또는 직접추가',
	`Field9`	datetime	NOT NULL,
	`Key2`	BIGINT	NOT NULL	COMMENT 'category를 명시하는 fk',
	`user_id`	BIGINT	NOT NULL
);

CREATE TABLE `Untitled6` (
	`Key2`	BIGINT	NOT NULL,
	`Field`	VARCHAR(300)	NULL	COMMENT '해당 지출에 대한 사용자의 메모',
	`Field2`	VARCHAR(1024)	NULL	COMMENT 'S3 스펙상 object key의 최대 길이인 1024',
	`Field3`	VARCHAR(63)	NULL,
	`Field4`	VARCHAR(100)	NULL,
	`Field5`	VARCHAR(255)	NULL
);

CREATE TABLE `challenge_day` (
	`id`	bigint	NOT NULL,
	`challenge_id`	bigint	NOT NULL,
	`day_date`	date	NOT NULL,
	`spent_amount`	int	NOT NULL	COMMENT '그날 EXPENSE 합계',
	`status`	varchar(10)	NOT NULL	COMMENT 'SUCCESS/OVER'
);

CREATE TABLE `Untitled7` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(30)	NOT NULL	COMMENT '배달/외식/편의점/카페/식재료 or 장보기/간식 or 디저트/술자리 또는 직접추가',
	`Field2`	BOOLEAN	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT '사용자 지정 카테고리일 경우 해당 user_id 명시, 시스템 지정 카테고리인 경우 관리자 id 명시'
);

CREATE TABLE `Untitled` (
	`user_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(30)	NOT NULL,
	`email`	VARCHAR(100)	NOT NULL,
	`public_user_code`	VARCHAR(30)	NOT NULL	COMMENT '챌린지 추가용',
	`profile_image_url`	VARCHAR(200)	NULL,
	`role`	VARCHAR(10)	NOT NULL	COMMENT 'USER/ADMIN',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'ACTIVE/DELETED/BLOCKED 등',
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled2` (
	`auth_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`login_type`	VARCHAR(20)	NOT NULL	COMMENT 'LOCAL/GOOGLE/KAKAO/NAVER',
	`email`	VARCHAR(100)	NULL,
	`password`	VARCHAR(255)	NULL,
	`provider_id`	VARCHAR(255)	NULL	COMMENT '소셜 로그인 기업에서 제공하는 사용자별 고유 ID',
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled4` (
	`minichallenge_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NOT NULL,
	`difficulty`	VARCHAR(20)	NOT NULL	COMMENT 'EASY / NORMAL / CHALLENGE',
	`period`	INT	NOT NULL,
	`consecutive_days`	INT	NOT NULL	DEFAULT 0,
	`Field`	BOOLEAN	NOT NULL	DEFAULT false,
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL
);

CREATE TABLE `challenge_weak_category` (
	`id`	bigint	NOT NULL,
	`challenge_id`	bigint	NOT NULL,
	`category`	varchar(50)	NOT NULL	COMMENT '배달/외식/편의점/카페/식재료 or 장보기/간식 or 디저트/술자리 또는 직접추가'
);

CREATE TABLE `Untitled3` (
	`token_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`token`	VARCHAR(500)	NOT NULL,
	`expired_at`	DATETIME	NOT NULL,
	`created_at`	DATETIME	NOT NULL
);

ALTER TABLE `challenge` ADD CONSTRAINT `PK_CHALLENGE` PRIMARY KEY (
	`id`
);

ALTER TABLE `Untitled5` ADD CONSTRAINT `PK_UNTITLED5` PRIMARY KEY (
	`Key`
);

ALTER TABLE `Untitled6` ADD CONSTRAINT `PK_UNTITLED6` PRIMARY KEY (
	`Key2`
);

ALTER TABLE `challenge_day` ADD CONSTRAINT `PK_CHALLENGE_DAY` PRIMARY KEY (
	`id`
);

ALTER TABLE `Untitled7` ADD CONSTRAINT `PK_UNTITLED7` PRIMARY KEY (
	`Key`
);

ALTER TABLE `Untitled` ADD CONSTRAINT `PK_UNTITLED` PRIMARY KEY (
	`user_id`
);

ALTER TABLE `Untitled2` ADD CONSTRAINT `PK_UNTITLED2` PRIMARY KEY (
	`auth_id`
);

ALTER TABLE `Untitled4` ADD CONSTRAINT `PK_UNTITLED4` PRIMARY KEY (
	`minichallenge_id`
);

ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `PK_CHALLENGE_WEAK_CATEGORY` PRIMARY KEY (
	`id`
);

ALTER TABLE `Untitled3` ADD CONSTRAINT `PK_UNTITLED3` PRIMARY KEY (
	`token_id`
);

ALTER TABLE `challenge` ADD CONSTRAINT `FK_Untitled_TO_challenge_1` FOREIGN KEY (
	`user_id2`
)
REFERENCES `Untitled` (
	`user_id`
);

ALTER TABLE `Untitled5` ADD CONSTRAINT `FK_Untitled7_TO_Untitled5_1` FOREIGN KEY (
	`Key2`
)
REFERENCES `Untitled7` (
	`Key`
);

ALTER TABLE `Untitled5` ADD CONSTRAINT `FK_Untitled_TO_Untitled5_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `Untitled` (
	`user_id`
);

ALTER TABLE `Untitled6` ADD CONSTRAINT `FK_Untitled5_TO_Untitled6_1` FOREIGN KEY (
	`Key2`
)
REFERENCES `Untitled5` (
	`Key`
);

ALTER TABLE `challenge_day` ADD CONSTRAINT `FK_challenge_TO_challenge_day_1` FOREIGN KEY (
	`challenge_id`
)
REFERENCES `challenge` (
	`id`
);

ALTER TABLE `Untitled7` ADD CONSTRAINT `FK_Untitled_TO_Untitled7_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `Untitled` (
	`user_id`
);

ALTER TABLE `Untitled2` ADD CONSTRAINT `FK_Untitled_TO_Untitled2_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `Untitled` (
	`user_id`
);

ALTER TABLE `Untitled4` ADD CONSTRAINT `FK_Untitled_TO_Untitled4_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `Untitled` (
	`user_id`
);

ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `FK_challenge_TO_challenge_weak_category_1` FOREIGN KEY (
	`challenge_id`
)
REFERENCES `challenge` (
	`id`
);

ALTER TABLE `Untitled3` ADD CONSTRAINT `FK_Untitled_TO_Untitled3_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `Untitled` (
	`user_id`
);
