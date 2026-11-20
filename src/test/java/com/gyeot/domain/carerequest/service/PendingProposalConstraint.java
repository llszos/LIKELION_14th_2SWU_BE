package com.gyeot.domain.carerequest.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "요청당 대기(PENDING) 수정안은 최대 1건" 제약을 테스트 DB에 만듭니다. (#73)
 *
 * RequestChangeProposal 주석대로 이 제약은 엔티티에 매핑하지 않은 DDL(생성 컬럼 + UNIQUE)이라,
 * 테스트 DB(create-drop)에는 자동으로 만들어지지 않습니다. 그래서 제약과 함께 동작하는지 확인하는 테스트에서 직접 만듭니다.
 * - pending_request_id: 수정안이 PENDING이면 요청 ID, 아니면 NULL인 계산 컬럼
 * - UNIQUE: NULL은 여러 개 허용되므로, PENDING 수정안만 요청당 1건으로 제한됨
 *
 * 운영 DB에 같은 제약이 있는지는 별도로 확인이 필요합니다. (레포에 DDL이 없고, 운영은 ddl-auto: validate)
 */
final class PendingProposalConstraint {

    static final String INDEX_NAME = "uk_proposals_pending_request";

    private PendingProposalConstraint() {
    }

    /** 제약이 없을 때만 만듭니다. (같은 DB에 여러 번 불러도 안전) */
    static void createIfAbsent(JdbcTemplate jdbcTemplate) {
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'request_change_proposals' and index_name = ?
                """, Integer.class, INDEX_NAME);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.execute("""
                alter table request_change_proposals
                  add column pending_request_id bigint
                    generated always as (case when status = 'PENDING' then request_id end) virtual,
                  add unique key %s (pending_request_id)
                """.formatted(INDEX_NAME));
    }
}