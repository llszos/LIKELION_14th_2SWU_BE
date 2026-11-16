package com.gyeot.domain.carerequest.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * "요청당 열린(마감되지 않은) 지정은 최대 1건" 제약을 테스트 DB에 만듭니다. (#71, ERD 6.2)
 *
 * 이 제약은 엔티티에 매핑하지 않은 DDL(생성 컬럼 + UNIQUE)이라 테스트 DB(create-drop)에는 자동으로 만들어지지 않습니다.
 * Flyway V1(#47)과 같은 이름·식으로 만들어, 다시 부탁하기가 제약이 있는 DB에서도 동작하는지 확인합니다.
 * - open_request_id: 지정이 마감되지 않았으면 요청 ID, 마감됐으면 NULL인 생성 컬럼
 * - UNIQUE: NULL은 여러 개 허용되므로, 열린 지정만 요청당 1건으로 제한됨
 *
 * #47이 병합돼 테스트 DB도 Flyway로 만들어지면 제약이 이미 있으므로 아무것도 하지 않습니다.
 */
final class OpenAssignmentConstraint {

    static final String INDEX_NAME = "uk_request_assignments_open_request";

    private OpenAssignmentConstraint() {
    }

    /**
     * 테스트 트랜잭션과 다른 새 연결에서 제약을 만듭니다. (트랜잭션 안의 @BeforeEach에서 불러도 됨)
     * MySQL은 DDL을 실행한 연결의 트랜잭션을 자동 커밋하므로, 테스트 트랜잭션 연결에서 실행하면 롤백이 깨집니다.
     * 테스트 트랜잭션이 아직 request_assignments를 건드리기 전에 불러야 합니다. (테이블 잠금 대기 방지)
     */
    static void createIfAbsentOutsideTransaction(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            createIfAbsent(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        } catch (SQLException e) {
            throw new IllegalStateException("열린 지정 1건 제약을 만들지 못했습니다.", e);
        }
    }

    /** 제약이 없을 때만 만듭니다. (같은 DB에 여러 번 불러도 안전) */
    static void createIfAbsent(JdbcTemplate jdbcTemplate) {
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'request_assignments' and index_name = ?
                """, Integer.class, INDEX_NAME);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.execute("""
                alter table request_assignments
                  add column open_request_id bigint
                    generated always as (case when closed_at is null then request_id end) virtual,
                  add unique key %s (open_request_id)
                """.formatted(INDEX_NAME));
    }
}
