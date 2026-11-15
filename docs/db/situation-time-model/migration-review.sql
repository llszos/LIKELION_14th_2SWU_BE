-- 검토용 템플릿. 모든 SQL은 주석이며 그대로 실행해도 DDL/DML을 수행하지 않습니다.
-- 실제 DB 미실행. 변환 규칙 승인 후에만 별도 실행본을 작성하십시오.

-- 사전 확인:
-- SHOW CREATE TABLE situations;
-- SELECT COUNT(*) AS row_count, MIN(start_date), MAX(end_date) FROM situations;
-- SELECT id, start_date, end_date, ended_at FROM situations ORDER BY id;

-- 확장 후보 (실제 스키마/기존 컬럼 존재 여부를 먼저 확인):
-- ALTER TABLE situations ADD COLUMN start_at DATETIME(6) NULL,
--                        ADD COLUMN end_at DATETIME(6) NULL;

-- 승인된 PK별 변환 결과의 예시 구조. 날짜→시각 계산 규칙은 여기에 정의하지 않습니다.
-- CREATE TABLE situation_time_mapping_review (
--   situation_id BIGINT PRIMARY KEY,
--   approved_start_at DATETIME(6) NOT NULL,
--   approved_end_at DATETIME(6) NOT NULL
-- );
-- 변환 승인 자료로 이 검토 테이블을 채우는 과정은 별도 작업입니다.

-- 매핑 누락, 여분 ID, 유효하지 않은 기간이 모두 0건이어야 합니다:
-- SELECT s.id FROM situations s LEFT JOIN situation_time_mapping_review m
--   ON m.situation_id = s.id WHERE m.situation_id IS NULL;
-- SELECT m.situation_id FROM situation_time_mapping_review m LEFT JOIN situations s
--   ON s.id = m.situation_id WHERE s.id IS NULL;
-- SELECT situation_id FROM situation_time_mapping_review
--   WHERE approved_start_at >= approved_end_at;

-- 쓰기 중지·백업·매핑 승인·누락 검사 후 새 컬럼만 채우는 후보:
-- START TRANSACTION;
-- UPDATE situations s JOIN situation_time_mapping_review m ON m.situation_id = s.id
-- SET s.start_at = m.approved_start_at, s.end_at = m.approved_end_at
-- WHERE s.start_at IS NULL AND s.end_at IS NULL;
-- 아래 검증 및 원본 날짜/ended_at 대조 후 승인된 실행자가 COMMIT 또는 ROLLBACK.
-- SELECT id FROM situations WHERE start_at IS NULL OR end_at IS NULL OR start_at >= end_at;
-- SELECT s.id FROM situations s JOIN situation_time_mapping_review m ON m.situation_id = s.id
-- WHERE NOT (s.start_at <=> m.approved_start_at) OR NOT (s.end_at <=> m.approved_end_at);

-- 새 컬럼 NOT NULL 및 구 컬럼 nullable 전환은 SHOW CREATE TABLE 결과에 맞춰 별도 승인.
-- 원본 컬럼 DROP/DELETE 없음. 복구 절차는 README.md 참고.
