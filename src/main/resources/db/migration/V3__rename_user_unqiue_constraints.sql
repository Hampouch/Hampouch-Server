-- V3__rename_user_unique_constraints.sql
--
-- 배경:
-- V1__current_schema.sql 에는 users.email / users.nickname의 unique 제약이
-- Hibernate가 V1 작성 시점에 자동 생성한 이름(UK6dotkott2kjsp8vw4d0m25fb7,
-- UK2ty1xmrrgtn89xt7kyxx6ta7h)으로 걸려 있다. AuthService.signup()이
-- DataIntegrityViolationException의 원인(email 중복 vs nickname 중복)을
-- 제약조건 이름으로 구분해야 하므로, 이름을 uk_user_email / uk_user_nickname으로
-- 고정해서 다시 건다.
--
-- (별도 이슈: sendEmailVerification / verifyEmail / socialLogin / setInitialNickname의
--  동시성 문제는 이 마이그레이션의 범위가 아니다. 신규 이슈로 분리해서 다룬다.)
--
-- V1은 Flyway가 체크섬으로 관리하는 고정된 마이그레이션이라, 이 두 제약 이름은
-- V1이 정상 적용된 모든 환경(dev/staging/prod)에서 동일하다.
--
-- 배포 직전 재확인 권장 (수동으로 스키마를 건드린 환경이 있었다면 이름이 다를 수 있음):
--   SELECT CONSTRAINT_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users';

ALTER TABLE users DROP INDEX UK6dotkott2kjsp8vw4d0m25fb7;
ALTER TABLE users DROP INDEX UK2ty1xmrrgtn89xt7kyxx6ta7h;

ALTER TABLE users ADD CONSTRAINT uk_user_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uk_user_nickname UNIQUE (nickname);