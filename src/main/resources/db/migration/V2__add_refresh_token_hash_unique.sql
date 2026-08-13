-- V2__add_refresh_token_hash_unique.sql
--
-- 목적: refresh_tokens.token_hash 에 unique 제약을 추가한다.
-- 동일 refresh token으로 동시에 재발급 요청이 들어왔을 때, 애플리케이션 레벨의
-- 비관적 락(AuthService.reissueToken의 findByTokenHashForUpdate)만으로는
-- 방어가 부족한 경우(예: 락 없이 새로 INSERT되는 경로)를 DB 레벨에서 마지막으로
-- 막기 위함이다.
--
-- 배포 전 반드시 아래 쿼리로 기존 중복이 없는지 확인할 것 (있으면 이 마이그레이션이 실패한다):
--   SELECT token_hash, COUNT(*) FROM refresh_tokens GROUP BY token_hash HAVING COUNT(*) > 1;

ALTER TABLE refresh_tokens ADD CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash);