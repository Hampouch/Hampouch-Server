ALTER TABLE challenge
    DROP COLUMN reset_by_payday,
    RENAME COLUMN payday_day TO fixed_day;
