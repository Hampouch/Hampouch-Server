-- reset_by_payday가 꺼진 행의 payday_day는 날짜 고정 설정이 아니라 남아 있던 값이다.
-- 이름만 바꾸면 그 행도 fixed_day IS NOT NULL이 되어 Challenge.isFixedDate()가 참이 되고,
-- 다음 주기 초안 조회·시작의 기준 챌린지(latest)로 잘못 올라탄다. 먼저 비운다.
UPDATE challenge SET payday_day = NULL WHERE reset_by_payday = 0;

ALTER TABLE challenge
    DROP COLUMN reset_by_payday,
    RENAME COLUMN payday_day TO fixed_day;

-- 실제로 챌린지에 입장한 날. 날짜 고정 챌린지는 고정일보다 늦게 입장할 수 있어
-- 주기 시작일(start_date)과 다를 수 있고, 미입력 자동 취소의 8일 기준이 이 값을 쓴다.
-- 기존 행은 늦은 입장 개념이 없었으므로 시작일과 같게 채운 뒤 NOT NULL로 조인다.
ALTER TABLE challenge ADD COLUMN activated_date DATE NULL AFTER start_date;
UPDATE challenge SET activated_date = start_date WHERE activated_date IS NULL;
ALTER TABLE challenge MODIFY COLUMN activated_date DATE NOT NULL;
