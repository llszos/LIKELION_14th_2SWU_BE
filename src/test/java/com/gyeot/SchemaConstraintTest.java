package com.gyeot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V1에 직접 넣은 DB 제약이 실제로 위반을 막는지 확인합니다. (#47, ERD 6.2)
 *
 * - 엔티티에 매핑되지 않은 제약(생성 컬럼 + UNIQUE, CHECK)이라 Hibernate validate로는 확인되지 않아 따로 검사합니다.
 * - 서비스 규칙과 상관없이 DB 제약만 보려고, 엔티티를 거치지 않고 SQL로 직접 넣습니다.
 * - 거부되는 경우와 함께, 종료된 이력 행은 여러 건 남을 수 있는지도 확인합니다. (활성 행에만 생성 컬럼 값이 들어가는지)
 * - 위반 확인은 예외 메시지에 제약 이름이 들어 있는지로 판단합니다. (MySQL은 UNIQUE 위반에 키 이름, CHECK 위반에 제약 이름을 돌려줌)
 * - 각 테스트는 끝나면 롤백됩니다. (@Transactional)
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 15, 0);

    @Autowired private JdbcTemplate jdbcTemplate;

    // === relationships: 같은 방향 활성 관계 1건, 자기 자신 금지 ===

    @Test
    void 같은_방향의_활성_관계는_하나만_있을_수_있다() {
        insertRelationship(1L, 2L, "ACTIVE");

        assertViolates(() -> insertRelationship(1L, 2L, "ACTIVE"), "uk_relationships_active_pair");
    }

    @Test
    void 유형이_달라도_같은_방향의_활성_관계는_하나만_있을_수_있다() {
        // 보호자 관계가 생기면 기존 나의 사람 관계를 종료하므로, 같은 방향에 두 유형이 함께 활성일 수 없음
        // (UNIQUE에 relationship_type이 실수로 들어가면 이 테스트가 실패)
        insertRelationship(1L, 2L, "MY_PERSON", "ACTIVE");

        assertViolates(() -> insertRelationship(1L, 2L, "GUARDIAN", "ACTIVE"), "uk_relationships_active_pair");
    }

    @Test
    void 종료된_관계_이력은_여러_건_남고_다시_맺거나_반대_방향을_맺을_수_있다() {
        assertThatCode(() -> {
            insertRelationship(1L, 2L, "ENDED");
            insertRelationship(1L, 2L, "ENDED");
            insertRelationship(1L, 2L, "ACTIVE");   // 종료 이력이 있어도 다시 맺을 수 있음
            insertRelationship(2L, 1L, "ACTIVE");   // 반대 방향은 다른 관계
        }).doesNotThrowAnyException();
    }

    @Test
    void 자기_자신과의_관계는_거부된다() {
        assertViolates(() -> insertRelationship(1L, 1L, "ACTIVE"), "chk_relationships_not_self");
    }

    // === situation_participants: 같은 상황 같은 사용자의 활성 참여 1건 ===

    @Test
    void 같은_상황에_같은_사용자의_활성_참여는_하나만_있을_수_있다() {
        Long situationId = insertSituation(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20));
        insertParticipant(situationId, 2L, "ACTIVE");

        assertViolates(() -> insertParticipant(situationId, 2L, "ACTIVE"), "uk_situation_participants_active");
    }

    @Test
    void 나가거나_제외된_참여_이력은_남고_다시_참여할_수_있다() {
        Long situationId = insertSituation(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20));

        assertThatCode(() -> {
            insertParticipant(situationId, 2L, "LEFT");
            insertParticipant(situationId, 2L, "REMOVED");
            insertParticipant(situationId, 2L, "ACTIVE"); // 이력이 있어도 다시 참여 가능
        }).doesNotThrowAnyException();
    }

    // === request_assignments: 요청당 열린 지정 1건 ===

    @Test
    void 요청당_열린_지정은_하나만_있을_수_있다() {
        Long requestId = insertRequest(NOW.plusDays(1), NOW.plusDays(1).plusHours(3));
        insertAssignment(requestId, 20L, null);

        // 다른 대상이어도 열린 지정이 이미 있으면 거부 (다시 부탁하기는 이전 지정을 먼저 마감해야 함)
        assertViolates(() -> insertAssignment(requestId, 21L, null), "uk_request_assignments_open_request");
    }

    @Test
    void 마감된_지정은_여러_건_남고_새_지정을_열_수_있다() {
        Long requestId = insertRequest(NOW.plusDays(1), NOW.plusDays(1).plusHours(3));

        assertThatCode(() -> {
            insertAssignment(requestId, 20L, NOW);
            insertAssignment(requestId, 21L, NOW);
            insertAssignment(requestId, 22L, null); // 마감된 지정이 있어도 열린 지정 1건은 가능
        }).doesNotThrowAnyException();
    }

    // === request_change_proposals: 요청당 PENDING 수정안 1건 ===

    @Test
    void 요청당_대기_중인_수정안은_하나만_있을_수_있다() {
        Long requestId = insertRequest(NOW.plusDays(1), NOW.plusDays(1).plusHours(3));
        Long assignmentId = insertAssignment(requestId, 20L, null);
        insertProposal(requestId, assignmentId, "PENDING");

        assertViolates(() -> insertProposal(requestId, assignmentId, "PENDING"), "uk_proposals_pending_request");
    }

    @Test
    void 처리된_수정안은_여러_건_남고_새_수정안을_대기시킬_수_있다() {
        Long requestId = insertRequest(NOW.plusDays(1), NOW.plusDays(1).plusHours(3));
        Long assignmentId = insertAssignment(requestId, 20L, null);

        assertThatCode(() -> {
            insertProposal(requestId, assignmentId, "ACCEPTED");
            insertProposal(requestId, assignmentId, "REJECTED");
            insertProposal(requestId, assignmentId, "PENDING");
        }).doesNotThrowAnyException();
    }

    // === CHECK: 기간 ===

    @Test
    void 상황_시작일이_종료일보다_늦으면_거부된다() {
        assertViolates(() -> insertSituation(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 16)),
                "chk_situations_period");
    }

    @Test
    void 상황_시작일과_종료일이_같으면_허용된다() {
        assertThatCode(() -> insertSituation(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 16)))
                .doesNotThrowAnyException(); // 시작일 <= 종료일
    }

    @Test
    void 요청_시작이_종료와_같거나_늦으면_거부된다() {
        LocalDateTime at = NOW.plusDays(1);

        assertViolates(() -> insertRequest(at, at), "chk_care_requests_period");                 // 같음
        assertViolates(() -> insertRequest(at.plusHours(1), at), "chk_care_requests_period");    // 늦음
    }

    @Test
    void 요청_일정이_비어_있으면_기간_CHECK를_통과한다() {
        // 급한 요청처럼 일정이 없는 경우 (일반 요청의 일정 필수 여부는 서비스에서 검증)
        assertThatCode(() -> insertRequest(null, null)).doesNotThrowAnyException();
    }

    // === 도우미 ===

    /** 예외가 DB 접근 예외이고 메시지에 제약 이름이 들어 있는지 */
    private static void assertViolates(Runnable insert, String constraintName) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraintName);
    }

    /** 방금 넣은 행의 ID (같은 트랜잭션, 같은 커넥션이라 마지막 INSERT의 값) */
    private Long lastInsertId() {
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
    }

    /** 나의 사람 관계 */
    private void insertRelationship(Long sourceUserId, Long targetUserId, String status) {
        insertRelationship(sourceUserId, targetUserId, "MY_PERSON", status);
    }

    private void insertRelationship(Long sourceUserId, Long targetUserId, String type, String status) {
        jdbcTemplate.update("""
                insert into relationships (created_at, source_user_id, target_user_id, relationship_type, status)
                values (?, ?, ?, ?, ?)
                """, NOW, sourceUserId, targetUserId, type, status);
    }

    private Long insertSituation(LocalDate startDate, LocalDate endDate) {
        jdbcTemplate.update("""
                insert into situations (start_date, end_date, created_at, creator_id, updated_at, situation_type, title, status)
                values (?, ?, ?, 1, ?, 'HOSPITAL', '병원 동행', 'ACTIVE')
                """, startDate, endDate, NOW, NOW);
        return lastInsertId();
    }

    private void insertParticipant(Long situationId, Long userId, String status) {
        jdbcTemplate.update("""
                insert into situation_participants (joined_at, situation_id, user_id, role, status)
                values (?, ?, ?, 'PARTICIPANT', ?)
                """, NOW, situationId, userId, status);
    }

    private Long insertRequest(LocalDateTime startsAt, LocalDateTime endsAt) {
        jdbcTemplate.update("""
                insert into care_requests (created_at, requester_id, updated_at, starts_at, ends_at,
                                           description, help_type, request_type, status)
                values (?, 1, ?, ?, ?, '병원 동행 부탁드려요', 'HOSPITAL', 'GENERAL', 'REQUESTED')
                """, NOW, NOW, startsAt, endsAt);
        return lastInsertId();
    }

    /** closedAt이 null이면 열린 지정 */
    private Long insertAssignment(Long requestId, Long targetUserId, LocalDateTime closedAt) {
        jdbcTemplate.update("""
                insert into request_assignments (assignment_order, created_at, request_id, response_deadline,
                                                 target_user_id, closed_at)
                values (1, ?, ?, ?, ?, ?)
                """, NOW, requestId, NOW.plusHours(24), targetUserId, closedAt);
        return lastInsertId();
    }

    private void insertProposal(Long requestId, Long assignmentId, String status) {
        jdbcTemplate.update("""
                insert into request_change_proposals (assignment_id, created_at, request_id, response_deadline,
                                                      changed_fields, status)
                values (?, ?, ?, ?, '[]', ?)
                """, assignmentId, NOW, requestId, NOW.plusHours(24), status);
    }
}
