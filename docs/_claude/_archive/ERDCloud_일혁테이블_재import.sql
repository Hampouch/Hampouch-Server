-- ============================================================================
-- ERDCloud — 일혁 소유 테이블만 싹 지우고 새로 넣기 (2026-07-07 작성 · 2026-07-14 #1 구현 코드와 대조 재확인)
-- 팀 테이블(유저·지출·카테고리·인증 등 나연/령준 것)은 절대 건드리지 말 것.
-- ============================================================================
--
-- 【1단계】 아래 일혁 소유 테이블을 전부 삭제 (이전 import 기준 — 현재 이 이름들로 존재):
--   challenge · challenge_day · challenge_weak_category · user_rest · challenge_adjustment
--   mini_challenge · challenge_mini_challenge · mini_challenge_day
--   ※ 0707 미니 본챌 독립화 개정: challenge_mini_challenge 삭제 · mini_challenge 재정의(유저 소유)
--     · mini_challenge_day FK 교체 · recommended_mini_challenge 신설 → 최종 8개(아래 CREATE 순서)
--
-- 【★절대 건드리지 말 것 — 전부 나연/령준 것】
--   Untitled(유저) · Untitled2(인증) · Untitled3(리프레시토큰)
--   Untitled5(지출) · Untitled6(지출상세) · Untitled7(카테고리) · CopyOfUntitled7(감정)
--   ★Untitled4 = 령준 '햄배틀'(versus/group) — 예전엔 구 미니챌린지였으나 지금은 햄배틀! 삭제 금지
--   Untitled8(벌칙) · Untitled9 · Untitled10(초대) · Untitled11~17(게시판) — 전부 남의 것
--
-- 【2단계】 아래 전체를 [Import]에 붙여넣기 (내부 관계선 4개는 FK로 자동, 유저 연결만 수동)
-- ============================================================================

CREATE TABLE `challenge` (
	`id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT 'FK → 유저 테이블(나연)',
	`duration_days`	INT	NOT NULL	COMMENT '3/7/14/31/N',
	`start_date`	DATE	NOT NULL,
	`end_date`	DATE	NOT NULL	COMMENT 'start + duration - 1',
	`budget_total`	INT	NOT NULL	COMMENT '기간 목표(원)',
	`daily_limit`	INT	NOT NULL	COMMENT '현재 유효 한도 · 최초 floor(budget/duration) · 조정 시 갱신',
	`reset_by_payday`	TINYINT(1)	NOT NULL	COMMENT '월급날 리셋(0714 부분 확정: 주기=월급날~월급날 · 기간형은 무관) · 현재는 저장만',
	`payday_day`	INT	NULL	COMMENT '1~31',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'IN_PROGRESS / SUCCESS / FAIL (휴식은 상태 아님)',
	`created_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `challenge_day` (
	`id`	BIGINT	NOT NULL,
	`challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge',
	`day_date`	DATE	NOT NULL	COMMENT '판정 날짜',
	`spent_amount`	INT	NOT NULL	COMMENT '그날 식비 합계(령준 연동)',
	`daily_limit`	INT	NOT NULL	COMMENT '판정 당시 한도 스냅샷(조정 대응) · V1 엔티티엔 아직 없음 — 한도 조정 기능 구현 시 추가',
	`status`	VARCHAR(10)	NOT NULL	COMMENT 'SUCCESS / OVER · 미입력일=0원 가정'
);

CREATE TABLE `challenge_weak_category` (
	`id`	BIGINT	NOT NULL,
	`challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge',
	`category`	VARCHAR(50)	NOT NULL	COMMENT '배달/외식/편의점/카페/간식/장보기/술자리 + 직접추가'
);

CREATE TABLE `user_rest` (
	`id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT 'FK → 유저 테이블 · 휴식 = 챌린지 사이 공백(유저 단위)',
	`rest_start_date`	DATE	NOT NULL	COMMENT '휴식 시작일',
	`planned_resume_date`	DATE	NOT NULL	COMMENT '복귀 예정일',
	`actual_resume_date`	DATE	NULL	COMMENT '실제 복귀일(복귀 전 null)',
	`created_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `challenge_adjustment` (
	`id`	BIGINT	NOT NULL,
	`challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → challenge · 챌린지당 최대 2회(행 수로 검증)',
	`old_daily_limit`	INT	NOT NULL	COMMENT '조정 전 한도',
	`new_daily_limit`	INT	NOT NULL	COMMENT '조정 후 = 현재 × 1.1 or 1.2',
	`adjusted_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `recommended_mini_challenge` (
	`id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NOT NULL	COMMENT '예: 오늘 커피 사먹지 않기 · 읽기 전용 프리셋',
	`duration_days`	INT	NOT NULL	COMMENT '오늘만=1 / 3 / 7 / 14 / 31'
);

CREATE TABLE `mini_challenge` (
	`id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT 'FK → 유저 테이블 · 미니는 유저 소유(본챌 독립, 0707)',
	`title`	VARCHAR(100)	NOT NULL	COMMENT '추천 복사분/커스텀 동일 · 수정 불가, 삭제 후 재생성',
	`duration_days`	INT	NOT NULL	COMMENT '오늘만=1 / 3 / 7 / 14 / 31',
	`start_date`	DATE	NOT NULL	COMMENT '시작일(추가한 날)',
	`end_date`	DATE	NOT NULL	COMMENT '스냅샷 = start + duration - 1 · 본챌 기간과 무관',
	`created_at`	DATETIME(6)	NOT NULL
);

CREATE TABLE `mini_challenge_day` (
	`id`	BIGINT	NOT NULL,
	`mini_challenge_id`	BIGINT	NOT NULL	COMMENT 'FK → mini_challenge',
	`check_date`	DATE	NOT NULL	COMMENT '행 존재 = 그날 체크 · 삭제 = 해제 · 미니 기간 내',
	`checked_at`	DATETIME(6)	NOT NULL
);

-- ── PK ──
ALTER TABLE `challenge` ADD CONSTRAINT `PK_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `challenge_day` ADD CONSTRAINT `PK_CHALLENGE_DAY` PRIMARY KEY (`id`);
ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `PK_CHALLENGE_WEAK_CATEGORY` PRIMARY KEY (`id`);
ALTER TABLE `user_rest` ADD CONSTRAINT `PK_USER_REST` PRIMARY KEY (`id`);
ALTER TABLE `challenge_adjustment` ADD CONSTRAINT `PK_CHALLENGE_ADJUSTMENT` PRIMARY KEY (`id`);
ALTER TABLE `recommended_mini_challenge` ADD CONSTRAINT `PK_RECOMMENDED_MINI_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `mini_challenge` ADD CONSTRAINT `PK_MINI_CHALLENGE` PRIMARY KEY (`id`);
ALTER TABLE `mini_challenge_day` ADD CONSTRAINT `PK_MINI_CHALLENGE_DAY` PRIMARY KEY (`id`);

-- ── UNIQUE ──
ALTER TABLE `challenge_day` ADD CONSTRAINT `UQ_CHALLENGE_DAY` UNIQUE (`challenge_id`, `day_date`);
ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `UQ_WEAK_CATEGORY` UNIQUE (`challenge_id`, `category`);
ALTER TABLE `mini_challenge_day` ADD CONSTRAINT `UQ_MINI_DAY` UNIQUE (`mini_challenge_id`, `check_date`);

-- ── FK (일혁 내부 관계 — import 시 관계선 자동으로 그려짐) ──
ALTER TABLE `challenge_day` ADD CONSTRAINT `FK_challenge_TO_challenge_day` FOREIGN KEY (`challenge_id`) REFERENCES `challenge` (`id`);
ALTER TABLE `challenge_weak_category` ADD CONSTRAINT `FK_challenge_TO_weak_category` FOREIGN KEY (`challenge_id`) REFERENCES `challenge` (`id`);
ALTER TABLE `challenge_adjustment` ADD CONSTRAINT `FK_challenge_TO_adjustment` FOREIGN KEY (`challenge_id`) REFERENCES `challenge` (`id`);
ALTER TABLE `mini_challenge_day` ADD CONSTRAINT `FK_mini_TO_mini_day` FOREIGN KEY (`mini_challenge_id`) REFERENCES `mini_challenge` (`id`);

-- ============================================================================
-- 【3단계】 유저 테이블(현재 이름 = `Untitled`) 연결만 수동:
--   challenge.user_id      → Untitled(유저)  : 비식별 1:N (점선)
--   user_rest.user_id      → Untitled(유저)  : 비식별 1:N (점선)
--   mini_challenge.user_id → Untitled(유저)  : 비식별 1:N (점선) · 미니 유저 소유(0707)
--   (recommended_mini_challenge = 프리셋 카탈로그, 관계 없음 — 연결 불필요)
-- ※ 관계선 그을 때 자동 생성되는 FK 컬럼이 위 컬럼과 중복되면 자동 생성 쪽 삭제.
-- ※ 삭제 전 challenge·user_rest 에 걸려 있던 기존 유저 선(user_id2 기준 — FK_Untitled_TO_challenge_1 등)은
--   테이블 삭제와 함께 사라짐 → 위처럼 user_id 로 다시 그으면 됨.
-- ============================================================================
