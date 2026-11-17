-- V2: 도움 요청 생성 (#69)
-- - request_assignments.response_deadline_hours: 요청자가 고른 응답 기한 시간(1~72). 고르지 않았으면 NULL(기본 24시간 규칙)
--   지정마다 저장해 두고 다시 부탁하기, 응답 기한 계산에 씁니다. 기존 지정은 값이 없어 NULL로 둡니다.
-- - care_requests.description: 상세 설명을 선택 입력으로 바꿔 NULL 허용
ALTER TABLE `request_assignments` ADD COLUMN `response_deadline_hours` int DEFAULT NULL;
ALTER TABLE `care_requests` MODIFY `description` text NULL;
