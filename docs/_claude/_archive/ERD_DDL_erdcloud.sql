-- =====================================================================
-- ERDCloud Import용 DDL — 일혁 역할 전체 ERD (docs/ERD.mermaid와 동기화)
-- 사용법: erdcloud.com → ERD 열기 → 왼쪽 하단 [Import] → 이 파일 내용 붙여넣기
--
-- ※ 이 파일은 다이어그램 공유용이며 Flyway 마이그레이션이 아님.
--    실제 스키마는 src/main/resources/db/migration/ (현재 V1 = challenge 3종).
--    [외부] = 타 팀원 소유(표시용 최소 컬럼) · [잠정] = PM 질문 확정 전 골격.
-- =====================================================================

-- [외부] 유저 — 로그인(나연) 소유. 표시용.
create table `user` (
    id bigint not null auto_increment comment '유저 PK (나연 로그인, JWT sub)',
    primary key (id)
) engine = InnoDB default charset = utf8mb4 comment = '[외부] 유저 - 나연';

-- 본 챌린지 (V1 마이그레이션과 동일 — 단 daily_limit 조정·challenge_day 스냅샷은 조정 기능 구현 시 추가)
create table challenge (
    id              bigint      not null auto_increment,
    user_id         bigint      not null comment 'FK → user',
    duration_days   int         not null comment '3/7/14/31/N',
    start_date      date        not null,
    end_date        date        not null comment 'start + duration - 1',
    budget_total    int         not null comment '기간 목표(원) — 최초 설정값',
    daily_limit     int         not null comment '현재 유효 한도 · 최초 = floor(budget_total / duration_days) · 조정 시 갱신(이력=challenge_adjustment)',
    reset_by_payday bit         not null comment '월급날 리셋 옵션',
    payday_day      int         null comment '1~31',
    status          varchar(20) not null comment 'IN_PROGRESS / SUCCESS / FAIL — 휴식은 챌린지 상태 아님(user_rest)',
    created_at      datetime(6) not null,
    primary key (id),
    constraint fk_challenge_user foreign key (user_id) references `user` (id)
) engine = InnoDB default charset = utf8mb4 comment = '본 챌린지';

create table challenge_day (
    id           bigint      not null auto_increment,
    challenge_id bigint      not null comment 'FK → challenge',
    day_date     date        not null comment '판정 날짜',
    spent_amount int         not null comment '그날 지출 합계(령준 데이터 연동)',
    daily_limit  int         not null comment '판정 당시 한도 스냅샷(조정 대응) — V1엔 없음, 조정 구현 시 마이그레이션 추가',
    status       varchar(10) not null comment 'SUCCESS(<=한도) / OVER(>한도) · 미입력일 = 0원 가정(0630 확정)',
    primary key (id),
    constraint uq_challenge_day unique (challenge_id, day_date),
    constraint fk_challenge_day_challenge foreign key (challenge_id) references challenge (id)
) engine = InnoDB default charset = utf8mb4 comment = '일별 판정';

create table challenge_weak_category (
    id           bigint      not null auto_increment,
    challenge_id bigint      not null comment 'FK → challenge',
    category     varchar(50) not null comment '확정: 지출과 동일 7종+직접추가(온보딩3, 2026-07-07) · 진행 중 수정 가능',
    primary key (id),
    constraint uq_weak_category unique (challenge_id, category),
    constraint fk_weak_category_challenge foreign key (challenge_id) references challenge (id)
) engine = InnoDB default charset = utf8mb4 comment = '약한 카테고리(챌린지별)';

-- 휴식 이력 — 챌린지 사이 공백(유저 단위). 진입점=결과 화면 확정 · 무제한/연장/자동종료 규칙은 결정됨(결정기록.md).
create table user_rest (
    id                  bigint      not null auto_increment,
    user_id             bigint      not null comment 'FK → user',
    rest_start_date     date        not null comment '휴식 시작일',
    planned_resume_date date        not null comment '복귀 예정일 = 시작 + 3일/1주/2주/직접',
    actual_resume_date  date        null comment '실제 복귀일(복귀 전 null) · 연장은 기간 재선택(자체 결정)',
    created_at          datetime(6) not null,
    primary key (id),
    constraint fk_user_rest_user foreign key (user_id) references `user` (id)
) engine = InnoDB default charset = utf8mb4 comment = '휴식 이력(챌린지 사이 공백, 유저 단위)';

-- 한도 조정 이력 — 화면 확정(챌린지당 최대 2회, 과거 기록 유지).
create table challenge_adjustment (
    id              bigint      not null auto_increment,
    challenge_id    bigint      not null comment 'FK → challenge · 2회 제한 검증 = 행 수',
    old_daily_limit int         not null comment '조정 전 한도',
    new_daily_limit int         not null comment '조정 후 한도 = 현재 × 1.1 or 1.2 (옵션 고정 확정)',
    adjusted_at     datetime(6) not null,
    primary key (id),
    constraint fk_adjustment_challenge foreign key (challenge_id) references challenge (id)
) engine = InnoDB default charset = utf8mb4 comment = '한도 조정 이력';

-- 추천 미니 챌린지 카탈로그 — 기획이 심는 고정 목록(읽기 전용, 관계 없음). 유저가 '추가하기' 시 값만 mini_challenge로 복사(A안, 0707).
create table recommended_mini_challenge (
    id            bigint       not null auto_increment,
    title         varchar(100) not null comment '예: 오늘 커피 사먹지 않기',
    duration_days int          not null comment '오늘만=1/3/7/14/31',
    primary key (id)
) engine = InnoDB default charset = utf8mb4 comment = '추천 미니 챌린지 프리셋(읽기 전용)';

-- 미니 챌린지 — 유저 소유·본 챌린지 독립(0707 서버회의). 정의+참여 합침(구 mini_challenge + challenge_mini_challenge). 추천/커스텀 통일(origin 없음).
create table mini_challenge (
    id            bigint       not null auto_increment,
    user_id       bigint       not null comment 'FK → user · 미니는 유저 소유(challenge_id 없음 — 본챌 독립)',
    title         varchar(100) not null comment '추천 복사분/커스텀 동일 · 수정 불가, 삭제 후 재생성(0630)',
    duration_days int          not null comment '확정: 오늘만=1/3/7/14/31 (스크린디자인 최신 기준)',
    start_date    date         not null comment '시작일(추가한 날)',
    end_date      date         not null comment '스냅샷 = start + duration - 1 · 본챌 기간과 무관(본챌 독립)',
    created_at    datetime(6)  not null,
    primary key (id),
    constraint fk_mini_challenge_user foreign key (user_id) references `user` (id)
) engine = InnoDB default charset = utf8mb4 comment = '미니 챌린지(유저 소유·본챌 독립)';

-- 미니 챌린지 일별 체크 — 행 존재 = 그날 체크, 해제 = 행 삭제. 반복형(매일 체크) 화면 확정.
create table mini_challenge_day (
    id                bigint      not null auto_increment,
    mini_challenge_id bigint      not null comment 'FK → mini_challenge',
    check_date        date        not null comment '미니 기간 내 날짜 · 과거 체크 허용(0707), 미래 차단',
    checked_at        datetime(6) not null,
    primary key (id),
    constraint uq_mini_day unique (mini_challenge_id, check_date),
    constraint fk_mcd_mini foreign key (mini_challenge_id) references mini_challenge (id)
) engine = InnoDB default charset = utf8mb4 comment = '미니 챌린지 일별 체크';

-- [외부] 지출 — 령준 소유. 표시용(식비만 · 감정 태그 null 허용).
create table expense (
    id         bigint      not null auto_increment,
    user_id    bigint      not null comment 'FK → user',
    spent_date date        not null,
    amount     int         not null comment '원',
    category   varchar(50) not null comment '확정 7종(배달/외식/편의점/카페/간식/장보기/술자리) + 직접추가',
    emotion    varchar(50) null comment '스트레스/보상/귀찮아서/그냥먹고싶어서/직접입력 · null=건너뛰기',
    primary key (id),
    constraint fk_expense_user foreign key (user_id) references `user` (id)
) engine = InnoDB default charset = utf8mb4 comment = '[외부] 지출 - 령준';

-- =====================================================================
-- ERDCloud는 Import 시 관계선을 자동으로 안 그려줌 → 아래대로 수동 연결.
--   비식별 1:N (점선 · FK가 일반 컬럼):
--     user 1─N challenge · user 1─N expense · user 1─N user_rest · user 1─N mini_challenge
--     challenge 1─N challenge_day · challenge 1─N challenge_weak_category
--     challenge 1─N challenge_adjustment · mini_challenge 1─N mini_challenge_day
--   recommended_mini_challenge = 프리셋 카탈로그(읽기 전용, 관계 없음)
-- =====================================================================
