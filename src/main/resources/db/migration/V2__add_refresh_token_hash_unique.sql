-- refresh_tokens.token_hash에 unique 제약을 추가한다. 그 전에 기존 중복 데이터를 정리
-- (해시 충돌이나 과거 애플리케이션 버그로 같은 해시가 중복 저장되어 있었을 가능성을 배제할 수 없음)
-- 중복 그룹에서는 token_id가 가장 큰(가장 최근에 생성된) 행만 남기고 나머지는 삭제한다
-- -> 재발급 로직상 더 최근 토큰이 유효한 토큰일 가능성이 높고, 오래된 중복 행은 이미 재발급 과정에서 폐기(revoked)됐을 가능성이 크다.

DELETE rt FROM refresh_tokens rt
INNER JOIN (
    SELECT token_hash, MAX(token_id) AS keep_id
    FROM refresh_tokens
    GROUP BY token_hash
    HAVING COUNT(*) > 1
) dup ON rt.token_hash = dup.token_hash AND rt.token_id <> dup.keep_id;

ALTER TABLE refresh_tokens ADD CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash);