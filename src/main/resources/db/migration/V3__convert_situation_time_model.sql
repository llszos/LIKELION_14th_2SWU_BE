-- #116 / #117: V1 (#131) -> V2 (#122) -> V3 (#116) -> V4 (#118).
-- Requires MySQL 8.0.16+ with enforced CHECK constraints (deployment target: 8.4).
-- Stop all writers and take a verified backup before applying; do not mix old/new apps.
-- MySQL DDL commits implicitly. This migration is not idempotent: inspect partial state
-- and Flyway history on failure; do not blindly rerun or repair.
-- Legacy DATE columns are intentionally removed after verified backfill (team decision).

-- Reject invalid/zero/incomplete dates and next-day overflow before persistent DDL.
-- Duplicate PRIMARY KEY fails without depending on CHECK/NOT NULL SQL-mode handling.
CREATE TEMPORARY TABLE situation_time_migration_guard (id INT PRIMARY KEY);
INSERT INTO situation_time_migration_guard (id) VALUES (1);
INSERT INTO situation_time_migration_guard (id)
SELECT 1 FROM situations
WHERE start_date IS NULL OR end_date IS NULL
   OR YEAR(start_date) < 1000 OR MONTH(start_date) = 0 OR DAYOFMONTH(start_date) = 0
   OR YEAR(end_date) < 1000 OR MONTH(end_date) = 0 OR DAYOFMONTH(end_date) = 0
   OR LAST_DAY(start_date) IS NULL OR LAST_DAY(end_date) IS NULL
   OR DAYOFMONTH(start_date) > DAYOFMONTH(LAST_DAY(start_date))
   OR DAYOFMONTH(end_date) > DAYOFMONTH(LAST_DAY(end_date))
   OR start_date > end_date OR end_date >= '9999-12-31'
LIMIT 1;

ALTER TABLE situations
    ADD COLUMN start_at DATETIME(6) NULL,
    ADD COLUMN end_at DATETIME(6) NULL;

-- Asia/Seoul wall-clock times, without UTC conversion: [start day, end day + 1).
UPDATE situations
SET start_at = CAST(start_date AS DATETIME(6)),
    end_at = CAST(DATE_ADD(end_date, INTERVAL 1 DAY) AS DATETIME(6));

-- Validate every converted row before enforcing new constraints/removing source dates.
INSERT INTO situation_time_migration_guard (id)
SELECT 1 FROM situations
WHERE start_at IS NULL OR end_at IS NULL OR start_at >= end_at
   OR NOT (start_at <=> CAST(start_date AS DATETIME(6)))
   OR NOT (end_at <=> CAST(DATE_ADD(end_date, INTERVAL 1 DAY) AS DATETIME(6)))
LIMIT 1;

ALTER TABLE situations
    MODIFY COLUMN start_at DATETIME(6) NOT NULL,
    MODIFY COLUMN end_at DATETIME(6) NOT NULL;

ALTER TABLE situations
    DROP CHECK chk_situations_period,
    ADD CONSTRAINT chk_situations_time_period CHECK (start_at < end_at),
    DROP COLUMN start_date,
    DROP COLUMN end_date;

-- ended_at, IDs, row count and other columns are not modified.
-- Restoring the old application requires an approved DB recovery, not only an image rollback.
DROP TEMPORARY TABLE situation_time_migration_guard;
