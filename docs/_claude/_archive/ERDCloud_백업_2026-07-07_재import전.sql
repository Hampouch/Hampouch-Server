-- ============================================================================
-- 팀 ERDCloud 전체 백업 — 2026-07-07 (일혁 테이블 재import 직전 상태)
-- 일혁이 ERDCloud에서 export한 원문 그대로. 문제 생기면 이걸 통째로 다시 import해 복구.
-- ※ 2026-07-06 백업(ERDCloud_백업_2026-07-06_갈아엎기전.sql)은 그 뒤 팀이 추가한
--   햄배틀(Untitled4/8/9/10)·감정(CopyOfUntitled7)·게시판(Untitled11~17) 등이 빠져 있어 복구용 아님.
--
-- 테이블 소유 메모:
--   일혁 = challenge · challenge_day · challenge_weak_category · challenge_rest
--          · mini_challenge · challenge_mini_challenge · mini_challenge_day
--   나연 = Untitled(유저) · Untitled2(인증) · Untitled3(리프레시토큰) · Untitled11~17(게시판)
--   령준 = Untitled5(지출) · Untitled6(지출상세) · Untitled7(카테고리) · CopyOfUntitled7(감정)
--          · Untitled4(햄배틀) · Untitled8(벌칙) · Untitled9 · Untitled10(초대)
-- ============================================================================

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
	`Field9`	datetime	NOT NULL,
	`Key2`	BIGINT	NOT NULL	COMMENT 'category를 명시하는 fk',
	`user_id`	BIGINT	NOT NULL,
	`Key3`	BIGINT	NOT NULL
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
	`status`	varchar(10)	NOT NULL	COMMENT 'SUCCESS/OVER',
	`Field`	VARCHAR(255)	NULL
);

CREATE TABLE `Untitled7` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(30)	NOT NULL	COMMENT '배달/외식/편의점/카페/식재료 or 장보기/간식 or 디저트/술자리 또는 직접추가',
	`Field2`	BOOLEAN	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT '사용자 지정 카테고리일 경우 해당 user_id 명시, 시스템 지정 카테고리인 경우 관리자 id 명시'
);

CREATE TABLE `Untitled` (
	`user_id`	BIGINT	NOT NULL,
	`email`	VARCHAR(100)	NOT NULL,
	`name`	VARCHAR(30)	NOT NULL,
	`public_user_code`	VARCHAR(30)	NOT NULL	COMMENT '중복 및 변경 불가, 챌린지 추가용',
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

CREATE TABLE `CopyOfUntitled7` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(30)	NOT NULL	COMMENT '스트레스/보상/귀찮아서/그냥 먹고 싶어서, etc',
	`Field2`	BOOLEAN	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT '사용자 지정 카테고리일 경우 해당 user_id 명시, 시스템 지정 카테고리인 경우 관리자 id 명시'
);

CREATE TABLE `Untitled4` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(10)	NOT NULL	COMMENT 'versus/group',
	`Field2`	VARCHAR(100)	NOT NULL	DEFAULT 같이 햄배틀 할 분!,
	`Field3`	INTEGER	NOT NULL	COMMENT '2 이상',
	`Field8`	INTEGER	NOT NULL	COMMENT '3/7/14/31',
	`Field4`	DATE	NOT NULL,
	`Field5`	DATE	NOT NULL,
	`Field6`	VARCHAR(10)	NOT NULL	COMMENT 'READY/ONGOING/TERMINATED',
	`Field7`	datetime	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`Key2`	BIGINT	NOT NULL
);

CREATE TABLE `challenge_rest` (
	`id`	BIGINT	NOT NULL,
	`challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge',
	`rest_start_date`	DATE	NOT NULL	COMMENT '휴식 시작일',
	`planned_resume_date`	DATE	NOT NULL	COMMENT '복귀 예정일 = 시작 + 3일/1주/2주/직접선택',
	`actual_resume_date`	DATE	NULL	COMMENT '실제 복귀일(복귀 전 null) · 갱신 규칙 PM Q9-4',
	`created_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `mini_challenge` (
	`id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NOT NULL	COMMENT '예: 오늘 커피 사먹지 않기',
	`duration_days`	INT	NOT NULL	COMMENT '오늘만=1/3/7/14/31 — 옵션 목록 PM Q14',
	`emoji`	VARCHAR(10)	NULL	COMMENT '커스텀 선택 항목',
	`origin`	VARCHAR(20)	NOT NULL	COMMENT 'RECOMMENDED(추천) / CUSTOM(직접 만들기)',
	`created_by`	BIGINT	NULL	COMMENT 'CUSTOM 만든 유저 · 재사용 범위 잠정(PM Q8)'
);

CREATE TABLE `challenge_mini_challenge` (
	`id`	BIGINT	NOT NULL,
	`challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge · 본 챌린지 하위 확정',
	`mini_challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → mini_challenge',
	`start_date`	DATE	NOT NULL	COMMENT '참여 시작일(잠정: 추가한 날)',
	`end_date`	DATE	NOT NULL	COMMENT '스냅샷 = start + duration - 1 · 본 챌린지 초과 처리 PM Q15',
	`created_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `mini_challenge_day` (
	`id`	BIGINT	NOT NULL,
	`challenge_mini_challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge_mini_challenge(참여)',
	`check_date`	DATE	NOT NULL	COMMENT '행 존재 = 그날 체크 · 해제 = 행 삭제',
	`checked_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `Untitled8` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(30)	NOT NULL	COMMENT '커피 쏘기/밥 사기/술 한 잔/영화 한 편 쏘기, etc',
	`Field2`	BOOLEAN	NOT NULL,
	`user_id`	BIGINT	NOT NULL
);

CREATE TABLE `Untitled9` (
	`Key`	BIGINT	NOT NULL,
	`Field`	INTEGER	NOT NULL	DEFAULT 0,
	`Field2`	INT	NULL,
	`Field3`	datetime	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`Key2`	BIGINT	NOT NULL,
	`Key3`	BIGINT	NOT NULL
);

CREATE TABLE `Untitled10` (
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(1024)	NOT NULL,
	`Field2`	VARCHAR(30)	NOT NULL	COMMENT 'PENDING/ACCEPTED/REJECTED/EXPIRED',
	`Field3`	datetime	NOT NULL,
	`Field4`	datetime	NOT NULL,
	`Key2`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`user_id2`	BIGINT	NULL	COMMENT '기본적으로 Battle 초대는 비지정 게시 링크이므로 NULL'
);

CREATE TABLE `Untitled11` (
	`post_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`post_type`	VARCHAR(10)	NOT NULL	COMMENT 'ENUM(TIP, FOOD, RECRUIT)',
	`category`	VARCHAR(20)	NOT NULL	COMMENT 'ENUM(GROCERY, COOKING, DISCOUNT, RECORD, ETC, FOOD_RECOMMEND, RECRUIT)',
	`title`	VARCHAR(100)	NOT NULL,
	`content`	TEXT	NULL,
	`view_count`	INT	NOT NULL	DEFAULT 0,
	`like_count`	INT	NOT NULL	DEFAULT 0,
	`comment_count`	INT	NOT NULL	DEFAULT 0,
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled12` (
	`post_id`	BIGINT	NOT NULL,
	`menu`	VARCHAR(100)	NOT NULL,
	`place`	VARCHAR(100)	NOT NULL,
	`price`	INT	NOT NULL,
	`taste_rating`	INT	NULL	DEFAULT 0,
	`cost_rating`	INT	NOT NULL	DEFAULT 0,
	`mood_rating`	INT	NOT NULL	DEFAULT 0,
	`created_at`	DATETIME	NOT NULL,
	`update_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled13` (
	`post_id`	BIGINT	NOT NULL,
	`Key`	BIGINT	NOT NULL,
	`Field`	VARCHAR(500)	NOT NULL,
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled14` (
	`post_image_id`	BIGINT	NOT NULL,
	`post_id`	BIGINT	NOT NULL,
	`image_url`	VARCHAR(1000)	NOT NULL,
	`image_key`	VARCHAR(500)	NOT NULL,
	`sort_order`	INT	NOT NULL,
	`created_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled15` (
	`comment_id`	BIGINT	NOT NULL,
	`post_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`comment_id2`	BIGINT	NULL	COMMENT 'NULL이면 일반 댓글',
	`content`	TEXT	NOT NULL,
	`is_deleted`	BOOLEAN	NOT NULL,
	`created_at`	DATETIME	NOT NULL,
	`updated_at`	DATETIME	NOT NULL,
	`deleted_at`	DATETIME	NULL
);

CREATE TABLE `Untitled16` (
	`like_id`	BIGINT	NOT NULL,
	`post_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`created_at`	DATETIME	NOT NULL
);

CREATE TABLE `Untitled17` (
	`bookmark_id`	BIGINT	NOT NULL,
	`post_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`created_at`	DATETIME	NOT NULL
);

ALTER TABLE `challenge` ADD CONSTRAINT `PK_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `Untitled5` ADD CONSTRAINT `PK_UNTITLED5` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled6` ADD CONSTRAINT `PK_UNTITLED6` PRIMARY KEY (`Key2`);
ALTER TABLE `challenge_day` ADD CONSTRAINT `PK_CHALLENGE_DAY` PRIMARY KEY (`id`);
ALTER TABLE `Untitled7` ADD CONSTRAINT `PK_UNTITLED7` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled` ADD CONSTRAINT `PK_UNTITLED` PRIMARY KEY (`user_id`);
ALTER TABLE `Untitled2` ADD CONSTRAINT `PK_UNTITLED2` PRIMARY KEY (`auth_id`);
ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `PK_CHALLENGE_WEAK_CATEGORY` PRIMARY KEY (`id`);
ALTER TABLE `Untitled3` ADD CONSTRAINT `PK_UNTITLED3` PRIMARY KEY (`token_id`);
ALTER TABLE `CopyOfUntitled7` ADD CONSTRAINT `PK_COPYOFUNTITLED7` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled4` ADD CONSTRAINT `PK_UNTITLED4` PRIMARY KEY (`Key`);
ALTER TABLE `challenge_rest` ADD CONSTRAINT `PK_CHALLENGE_REST` PRIMARY KEY (`id`);
ALTER TABLE `mini_challenge` ADD CONSTRAINT `PK_MINI_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `challenge_mini_challenge` ADD CONSTRAINT `PK_CHALLENGE_MINI_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `mini_challenge_day` ADD CONSTRAINT `PK_MINI_CHALLENGE_DAY` PRIMARY KEY (`id`);
ALTER TABLE `Untitled8` ADD CONSTRAINT `PK_UNTITLED8` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled9` ADD CONSTRAINT `PK_UNTITLED9` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled10` ADD CONSTRAINT `PK_UNTITLED10` PRIMARY KEY (`Key`);
ALTER TABLE `Untitled11` ADD CONSTRAINT `PK_UNTITLED11` PRIMARY KEY (`post_id`);
ALTER TABLE `Untitled12` ADD CONSTRAINT `PK_UNTITLED12` PRIMARY KEY (`post_id`);
ALTER TABLE `Untitled13` ADD CONSTRAINT `PK_UNTITLED13` PRIMARY KEY (`post_id`);
ALTER TABLE `Untitled14` ADD CONSTRAINT `PK_UNTITLED14` PRIMARY KEY (`post_image_id`);
ALTER TABLE `Untitled15` ADD CONSTRAINT `PK_UNTITLED15` PRIMARY KEY (`comment_id`);
ALTER TABLE `Untitled16` ADD CONSTRAINT `PK_UNTITLED16` PRIMARY KEY (`like_id`);
ALTER TABLE `Untitled17` ADD CONSTRAINT `PK_UNTITLED17` PRIMARY KEY (`bookmark_id`);

ALTER TABLE `challenge` ADD CONSTRAINT `FK_Untitled_TO_challenge_1` FOREIGN KEY (`user_id2`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled5` ADD CONSTRAINT `FK_Untitled7_TO_Untitled5_1` FOREIGN KEY (`Key2`) REFERENCES `Untitled7` (`Key`);
ALTER TABLE `Untitled5` ADD CONSTRAINT `FK_Untitled_TO_Untitled5_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled5` ADD CONSTRAINT `FK_CopyOfUntitled7_TO_Untitled5_1` FOREIGN KEY (`Key3`) REFERENCES `CopyOfUntitled7` (`Key`);
ALTER TABLE `Untitled6` ADD CONSTRAINT `FK_Untitled5_TO_Untitled6_1` FOREIGN KEY (`Key2`) REFERENCES `Untitled5` (`Key`);
ALTER TABLE `challenge_day` ADD CONSTRAINT `FK_challenge_TO_challenge_day_1` FOREIGN KEY (`challenge_id`) REFERENCES `challenge` (`id`);
ALTER TABLE `Untitled7` ADD CONSTRAINT `FK_Untitled_TO_Untitled7_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled2` ADD CONSTRAINT `FK_Untitled_TO_Untitled2_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `FK_challenge_TO_challenge_weak_category_1` FOREIGN KEY (`challenge_id`) REFERENCES `challenge` (`id`);
ALTER TABLE `Untitled3` ADD CONSTRAINT `FK_Untitled_TO_Untitled3_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `CopyOfUntitled7` ADD CONSTRAINT `FK_Untitled_TO_CopyOfUntitled7_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled4` ADD CONSTRAINT `FK_Untitled_TO_Untitled4_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled4` ADD CONSTRAINT `FK_Untitled8_TO_Untitled4_1` FOREIGN KEY (`Key2`) REFERENCES `Untitled8` (`Key`);
ALTER TABLE `Untitled8` ADD CONSTRAINT `FK_Untitled_TO_Untitled8_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled9` ADD CONSTRAINT `FK_Untitled_TO_Untitled9_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled9` ADD CONSTRAINT `FK_Untitled10_TO_Untitled9_1` FOREIGN KEY (`Key2`) REFERENCES `Untitled10` (`Key`);
ALTER TABLE `Untitled9` ADD CONSTRAINT `FK_Untitled4_TO_Untitled9_1` FOREIGN KEY (`Key3`) REFERENCES `Untitled4` (`Key`);
ALTER TABLE `Untitled10` ADD CONSTRAINT `FK_Untitled4_TO_Untitled10_1` FOREIGN KEY (`Key2`) REFERENCES `Untitled4` (`Key`);
ALTER TABLE `Untitled10` ADD CONSTRAINT `FK_Untitled_TO_Untitled10_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled10` ADD CONSTRAINT `FK_Untitled_TO_Untitled10_2` FOREIGN KEY (`user_id2`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled11` ADD CONSTRAINT `FK_Untitled_TO_Untitled11_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled12` ADD CONSTRAINT `FK_Untitled11_TO_Untitled12_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled13` ADD CONSTRAINT `FK_Untitled11_TO_Untitled13_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled13` ADD CONSTRAINT `FK_Untitled4_TO_Untitled13_1` FOREIGN KEY (`Key`) REFERENCES `Untitled4` (`Key`);
ALTER TABLE `Untitled14` ADD CONSTRAINT `FK_Untitled11_TO_Untitled14_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled15` ADD CONSTRAINT `FK_Untitled11_TO_Untitled15_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled15` ADD CONSTRAINT `FK_Untitled_TO_Untitled15_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled15` ADD CONSTRAINT `FK_Untitled15_TO_Untitled15_1` FOREIGN KEY (`comment_id2`) REFERENCES `Untitled15` (`comment_id`);
ALTER TABLE `Untitled16` ADD CONSTRAINT `FK_Untitled11_TO_Untitled16_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled16` ADD CONSTRAINT `FK_Untitled_TO_Untitled16_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
ALTER TABLE `Untitled17` ADD CONSTRAINT `FK_Untitled11_TO_Untitled17_1` FOREIGN KEY (`post_id`) REFERENCES `Untitled11` (`post_id`);
ALTER TABLE `Untitled17` ADD CONSTRAINT `FK_Untitled_TO_Untitled17_1` FOREIGN KEY (`user_id`) REFERENCES `Untitled` (`user_id`);
