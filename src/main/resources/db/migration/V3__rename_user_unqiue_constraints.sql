-- V1__current_schema.sql 에는 users.email / users.nickname의 unique 제약이
-- Hibernate가 V1 작성 시점에 자동 생성한 이름(UK6dotkott2kjsp8vw4d0m25fb7,
-- UK2ty1xmrrgtn89xt7kyxx6ta7h)으로 걸려 있다. AuthService.signup()이
-- DataIntegrityViolationException의 원인(email 중복 vs nickname 중복)을
-- 제약조건 이름으로 구분해야 하므로, 이름을 uk_user_email / uk_user_nickname으로
-- 고정해서 다시 건다.
--
-- DROP INDEX + ADD CONSTRAINT로 나누면 MySQL DDL의 자동 커밋 특성상 중간에 실패할 경우
-- unique 제약이 아예 없는 상태로 남을 수 있다. 하나의 ALTER TABLE 문 안에서
-- RENAME INDEX만 사용해 이름만 바꾸고 제약 자체는 한 번도 사라지지 않게 한다.
--
-- 배포 직전 재확인 권장 (수동으로 스키마를 건드린 환경이 있었다면 이름이 다를 수 있음):
--   SELECT CONSTRAINT_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users';

ALTER TABLE users
    RENAME INDEX UK6dotkott2kjsp8vw4d0m25fb7 TO uk_user_email,
    RENAME INDEX UK2ty1xmrrgtn89xt7kyxx6ta7h TO uk_user_nickname;