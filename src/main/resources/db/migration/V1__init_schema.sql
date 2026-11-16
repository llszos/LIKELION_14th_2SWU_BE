-- V1: 초기 스키마 (#47)
--
-- 기준: develop d8be7f0의 엔티티가 만드는 스키마 + ERD 6.2의 DB 제약 중 엔티티에 매핑되지 않은 것
-- - 테이블, 컬럼, 인덱스, UNIQUE: 일회용 MySQL에서 Hibernate(ddl-auto=create)로 만든 DDL을 그대로 옮겼습니다. (처음 작성 기준 develop af9ada6)
--   그 뒤 develop에 추가된 user_consents(#106)는 아직 공유 DB에 V1을 적용하기 전이라 V1에 함께 넣었습니다. (UserConsent 엔티티와 같은 정의)
-- - FK: 엔티티가 연관관계로 매핑해 Hibernate가 만드는 7개만 둡니다. ERD의 나머지 FK(users.id 참조 등)는
--   지금 삭제 로직과 데이터 모델(서비스가 명시적으로 정리, 권한의 information_id를 코드가 비움 등)을 유지하는 임시 범위로 빼고,
--   ERD와의 차이는 팀에 공유해 후속으로 검토합니다.
-- - FK 삭제 정책: ON DELETE를 지정하지 않습니다. (기본 RESTRICT, ERD 6.6 "삭제는 FK CASCADE에 맡기지 않고 서비스에서 명시적으로 삭제")
-- - 이름: Hibernate가 임의로 만든 FK, UNIQUE 이름만 읽기 쉬운 이름으로 바꿨습니다. 엔티티에 이름을 적은 제약은 그대로입니다.
-- - 엔티티가 없는 테이블(알림, 감사 메시지)은 포함하지 않습니다.
-- - 이후 스키마 변경은 V2부터 새 파일로 추가합니다. (V2 #122, V3 #116, V4 #118 배정) 공유 DB에 적용된 뒤에는 V1을 수정하지 않습니다.

-- === 사용자, 초대, 관계 (A) ===

CREATE TABLE `users` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  `name` varchar(50) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `account_status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`) -- 탈퇴 시 email을 비우므로 같은 이메일로 다시 가입 가능 (UNIQUE는 NULL 중복 허용)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `invitations` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invitee_id` bigint DEFAULT NULL,
  `inviter_id` bigint NOT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `personal_label` varchar(50) DEFAULT NULL,
  `message` text,
  `token_hash` varchar(255) NOT NULL,
  `invitation_type` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitations_token_hash` (`token_hash`) -- ERD 6.2 초대 토큰 중복 금지
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `relationships` (
  `guardian_priority` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_user_id` bigint NOT NULL,
  `target_user_id` bigint NOT NULL,
  `personal_label` varchar(50) DEFAULT NULL,
  `relationship_type` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  -- ERD 6.2 같은 방향 활성 관계 중복 금지: 활성 행에만 값이 들어가는 생성 컬럼 (종료된 행은 NULL이라 이력으로 여러 개 가능)
  `active_source_user_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `source_user_id` END) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relationships_active_pair` (`active_source_user_id`, `target_user_id`),
  KEY `idx_relationships_source_type_status` (`source_user_id`,`relationship_type`,`status`),
  KEY `idx_relationships_target_type_status` (`target_user_id`,`relationship_type`,`status`),
  CONSTRAINT `chk_relationships_not_self` CHECK (`source_user_id` <> `target_user_id`) -- ERD 6.2 자기 자신과 관계 금지
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `emergency_contacts` (
  `priority` int NOT NULL,
  `contact_user_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_emergency_contacts_user_contact` (`user_id`,`contact_user_id`),
  UNIQUE KEY `uk_emergency_contacts_user_priority` (`user_id`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refresh_tokens` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `revoked_at` datetime(6) DEFAULT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `session_id` varchar(36) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_tokens_token_hash` (`token_hash`),
  KEY `idx_refresh_tokens_session_id` (`session_id`),
  KEY `idx_refresh_tokens_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 개인정보 제공 동의 기록 (#106). 탈퇴 후에도 동의 증빙을 보존하므로 users FK 없이 사용자 ID만 저장
CREATE TABLE `user_consents` (
  `consented_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `consent_type` varchar(30) NOT NULL,
  `policy_version` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_consents_user_type_version` (`user_id`,`consent_type`,`policy_version`) -- 같은 사용자, 동의 유형, 정책 버전의 중복 기록 금지
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- === 상황 (B) ===

CREATE TABLE `situations` (
  `end_date` date NOT NULL,
  `start_date` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `creator_id` bigint NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `situation_type` varchar(30) NOT NULL,
  `title` varchar(150) NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_situations_creator_id_status` (`creator_id`,`status`),
  -- ERD 6.2 시작일 <= 종료일 (#116에서 start_at, end_at으로 바꿀 때 이 CHECK도 함께 교체)
  CONSTRAINT `chk_situations_period` CHECK (`start_date` <= `end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `situation_participants` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `joined_at` datetime(6) NOT NULL,
  `left_at` datetime(6) DEFAULT NULL,
  `situation_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  -- ERD 6.2 같은 상황, 같은 사용자의 활성 참여 중복 금지 (나가기, 제외된 행은 NULL이라 이력으로 남음)
  `active_situation_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `situation_id` END) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_situation_participants_active` (`active_situation_id`, `user_id`),
  KEY `idx_situation_participants_situation_id_status` (`situation_id`,`status`),
  KEY `idx_situation_participants_user_id_status` (`user_id`,`status`),
  CONSTRAINT `fk_situation_participants_situation` FOREIGN KEY (`situation_id`) REFERENCES `situations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- === 도움 요청 (C) ===

CREATE TABLE `care_requests` (
  `canceled_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `ends_at` datetime(6) DEFAULT NULL,
  `expired_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `requester_id` bigint NOT NULL,
  `situation_id` bigint DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `starts_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `description` text NOT NULL,
  `place` text,
  `cancel_reason` varchar(40) DEFAULT NULL,
  `checklist` json DEFAULT NULL,
  `help_type` varchar(30) NOT NULL,
  `request_type` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_care_requests_situation_id_status` (`situation_id`,`status`),
  KEY `idx_care_requests_requester_id_created_at` (`requester_id`,`created_at`),
  KEY `idx_care_requests_status_starts_at` (`status`,`starts_at`),
  KEY `idx_care_requests_status_ends_at` (`status`,`ends_at`),
  -- ERD 6.2 시작 < 종료 (급한 요청처럼 둘 중 하나가 NULL이면 통과, 일반 요청의 필수 여부는 서비스에서 검증)
  CONSTRAINT `chk_care_requests_period` CHECK (`starts_at` < `ends_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `request_assignments` (
  `assignment_order` int NOT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notice_closed_at` datetime(6) DEFAULT NULL,
  `request_id` bigint NOT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `response_deadline` datetime(6) NOT NULL,
  `target_user_id` bigint NOT NULL,
  `close_reason` varchar(40) DEFAULT NULL,
  `notice_reason` varchar(30) DEFAULT NULL,
  `response_status` varchar(30) DEFAULT NULL,
  -- ERD 6.2 요청당 현재(열린) 지정 최대 1건 (#85): 마감되지 않은 지정에만 요청 ID가 들어가는 생성 컬럼
  `open_request_id` bigint GENERATED ALWAYS AS (CASE WHEN `closed_at` IS NULL THEN `request_id` END) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_assignments_target` (`request_id`,`target_user_id`),
  UNIQUE KEY `uk_request_assignments_open_request` (`open_request_id`),
  KEY `idx_assignments_request_id_closed_at` (`request_id`,`closed_at`),
  KEY `idx_assignments_target_user_id_closed_at_response_deadline` (`target_user_id`,`closed_at`,`response_deadline`),
  CONSTRAINT `fk_request_assignments_request` FOREIGN KEY (`request_id`) REFERENCES `care_requests` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `request_change_proposals` (
  `assignment_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `proposed_ends_at` datetime(6) DEFAULT NULL,
  `proposed_starts_at` datetime(6) DEFAULT NULL,
  `request_id` bigint NOT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `response_deadline` datetime(6) NOT NULL,
  `proposed_place` text,
  `changed_fields` json NOT NULL,
  `proposed_information_ids` json DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  -- ERD 6.2 요청당 PENDING 제안 최대 1건 (#113): 이름과 식은 테스트 도우미 PendingProposalConstraint와 같게 맞춤
  `pending_request_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN `request_id` END) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proposals_pending_request` (`pending_request_id`),
  KEY `idx_proposals_assignment_id_status_response_deadline` (`assignment_id`,`status`,`response_deadline`),
  CONSTRAINT `fk_request_change_proposals_request` FOREIGN KEY (`request_id`) REFERENCES `care_requests` (`id`),
  CONSTRAINT `fk_request_change_proposals_assignment` FOREIGN KEY (`assignment_id`) REFERENCES `request_assignments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `request_change_events` (
  `actor_user_id` bigint DEFAULT NULL,
  `assignment_id` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL,
  `proposal_id` bigint DEFAULT NULL,
  `request_id` bigint NOT NULL,
  `event_details` json DEFAULT NULL,
  `event_type` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_change_events_request_id_occurred_at` (`request_id`,`occurred_at`),
  KEY `idx_change_events_assignment_id_occurred_at` (`assignment_id`,`occurred_at`),
  KEY `idx_change_events_proposal_id` (`proposal_id`), -- FK용 인덱스 (Hibernate가 FK 이름으로 만들던 것을 이름만 정리)
  CONSTRAINT `fk_request_change_events_request` FOREIGN KEY (`request_id`) REFERENCES `care_requests` (`id`),
  CONSTRAINT `fk_request_change_events_assignment` FOREIGN KEY (`assignment_id`) REFERENCES `request_assignments` (`id`),
  CONSTRAINT `fk_request_change_events_proposal` FOREIGN KEY (`proposal_id`) REFERENCES `request_change_proposals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- === 안심카드, 접근 권한, 접근 이력 (D) ===

CREATE TABLE `safety_information` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `title` varchar(1000) DEFAULT NULL,
  `information_value` text NOT NULL,
  `information_type` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_safety_information_owner_id_information_type` (`owner_id`,`information_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- information_id는 FK 없이 둡니다. 항목 삭제 시 코드가 권한의 information_id를 직접 비웁니다. (ERD는 ON DELETE SET NULL, 후속 검토)
CREATE TABLE `request_information_permissions` (
  `assignment_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `granted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `information_id` bigint DEFAULT NULL,
  `request_id` bigint NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `information_type` varchar(40) NOT NULL,
  `revoke_reason` varchar(40) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_permissions_request_id_status` (`request_id`,`status`),
  KEY `idx_permissions_assignment_id_status` (`assignment_id`,`status`),
  KEY `idx_permissions_information_id_status` (`information_id`,`status`),
  KEY `idx_permissions_status_expires_at` (`status`,`expires_at`),
  KEY `idx_permissions_status_revoked_at` (`status`,`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `information_access_logs` (
  `actor_user_id` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `information_id` bigint NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `owner_user_id` bigint NOT NULL,
  `permission_id` bigint DEFAULT NULL,
  `event_type` varchar(40) NOT NULL,
  `information_type` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_access_logs_owner_user_id_actor_user_id_occurred_at` (`owner_user_id`,`actor_user_id`,`occurred_at`),
  KEY `idx_access_logs_owner_user_id_information_type_occurred_at` (`owner_user_id`,`information_type`,`occurred_at`),
  KEY `idx_access_logs_owner_user_id_information_id_occurred_at` (`owner_user_id`,`information_id`,`occurred_at`),
  KEY `idx_access_logs_occurred_at` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
