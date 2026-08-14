-- 테스트 전용 MySQL 컨테이너 부팅 시 root 권한으로 실행되는 초기화 스크립트.
-- @ServiceConnection이 기본으로 주는 test/test 계정은 자신의 기본 스키마(test)에만
-- 권한이 있어, 마이그레이션 중간 상태를 재현하려고 별도 스키마를 만드는 테스트
-- (MySqlTestSchemas)에서 CREATE DATABASE 등 관리자 작업을 할 수 없다.
-- 이 스크립트로 test 계정에 모든 권한을 부여해 별도 root 계정 관리 없이 해결한다.
GRANT ALL PRIVILEGES ON *.* TO 'test'@'%';
FLUSH PRIVILEGES;