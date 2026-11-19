package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에 요청·지정을 저장하고, 요청 상태별로 담당자 값이 맞게 나오는지 확인합니다.
 * @Import로 테스트할 구현체를 직접 등록합니다. (@DataJpaTest는 @Service를 자동으로 띄우지 않음)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, CareRequestSharingReaderImpl.class})
@ActiveProfiles("test")
@Testcontainers
public class CareRequestSharingReaderImplTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;
    private static final Long NEXT_TARGET_ID = 30L;
    private static final Long STRANGER_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Autowired private CareRequestSharingReader reader; // 인터페이스 타입으로 받음 (D가 쓰는 방식)
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private EntityManager entityManager;

    // === findById: 요청 상태별 담당자 값 ===

    @Test
    void 수락된_요청은_현재_담당자가_담긴다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        flushAndClear();

        SharingRequestInfo info = reader.findById(request.getId()).orElseThrow();

        assertThat(info.status()).isEqualTo(CareRequestStatus.ACCEPTED);
        assertThat(info.assignmentId()).isEqualTo(assignment.getId());
        assertThat(info.assigneeId()).isEqualTo(TARGET_ID);
        assertThat(info.acceptedAt()).isEqualTo(NOW);
        assertThat(info.requesterId()).isEqualTo(REQUESTER_ID);
        assertThat(info.requestType()).isEqualTo(RequestType.GENERAL);
        assertThat(info.situationId()).isEqualTo(SITUATION_ID);
        assertThat(info.helpType()).isEqualTo(HelpType.HOSPITAL);
    }

    @Test
    void 완료된_요청은_마지막_담당자가_남는다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        request.start(NOW.plusDays(1));
        request.complete(NOW.plusDays(1).plusHours(3)); // 완료는 지정을 마감하지 않음 (ERD 9.1)
        flushAndClear();

        SharingRequestInfo info = reader.findById(request.getId()).orElseThrow();

        assertThat(info.status()).isEqualTo(CareRequestStatus.COMPLETED);
        assertThat(info.assigneeId()).isEqualTo(TARGET_ID);
        assertThat(info.assignmentId()).isEqualTo(assignment.getId());
    }

    @Test
    void 수락_후_취소된_요청은_담당자가_null이다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
        assignment.close(AssignmentCloseReason.REQUEST_ENDED, NOW); // 취소는 지정을 함께 마감
        flushAndClear();

        assertNoAssignee(reader.findById(request.getId()).orElseThrow(), CareRequestStatus.CANCELED);
    }

    @Test
    void 만료된_요청은_담당자가_null이다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        request.expire(NOW.plusDays(1));
        assignment.close(AssignmentCloseReason.REQUEST_ENDED, NOW.plusDays(1));
        flushAndClear();

        assertNoAssignee(reader.findById(request.getId()).orElseThrow(), CareRequestStatus.EXPIRED);
    }

    @Test
    void 수락_전_대상자는_담당자가_아니다() {
        CareRequest request = saveRequest();
        saveAssignment(request, TARGET_ID, 1); // 열려 있지만 수락 안 함
        flushAndClear();

        assertNoAssignee(reader.findById(request.getId()).orElseThrow(), CareRequestStatus.REQUESTED);
    }

    @Test
    void 수락을_철회하면_담당자가_null이다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW);
        request.revertToRequested();
        flushAndClear();

        assertNoAssignee(reader.findById(request.getId()).orElseThrow(), CareRequestStatus.REQUESTED);
    }

    @Test
    void 없는_요청은_빈_Optional이다() {
        assertThat(reader.findById(12345L)).isEmpty();
    }

    // === findByIdForUpdate (D #46 다시 허용) ===

    @Test
    void 잠금_조회는_일반_조회와_같은_값을_돌려준다() {
        CareRequest request = saveRequest();
        accept(request, saveAssignment(request, TARGET_ID, 1));
        flushAndClear();

        assertThat(reader.findByIdForUpdate(request.getId())).isEqualTo(reader.findById(request.getId()));
        assertThat(reader.findByIdForUpdate(request.getId()).orElseThrow().assigneeId()).isEqualTo(TARGET_ID);
    }

    @Test
    void 잠금_조회는_없는_요청이면_빈_Optional이다() {
        assertThat(reader.findByIdForUpdate(12345L)).isEmpty();
    }

    @Test
    void 같은_트랜잭션에서_먼저_읽어_둔_값이_있어도_잠금_조회는_DB의_최신_값을_돌려준다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        flushAndClear();
        reader.findById(request.getId()); // 요청·지정 엔티티가 메모리에 남음 (수락됨, 담당자 있음)

        // 다른 트랜잭션이 취소하고 지정을 마감한 것처럼 DB만 바꿈 (메모리의 엔티티는 예전 값 그대로)
        entityManager.createNativeQuery("update care_requests set status = 'CANCELED' where id = :id")
                .setParameter("id", request.getId()).executeUpdate();
        entityManager.createNativeQuery("update request_assignments set closed_at = :now, close_reason = 'REQUEST_ENDED' "
                        + "where id = :id")
                .setParameter("now", NOW).setParameter("id", assignment.getId()).executeUpdate();

        assertNoAssignee(reader.findByIdForUpdate(request.getId()).orElseThrow(), CareRequestStatus.CANCELED);
    }

    // === findAllByIds ===

    @Test
    void 여러_요청을_한_번에_조회하고_없는_ID는_빠진다() {
        CareRequest accepted = saveRequest();
        accept(accepted, saveAssignment(accepted, TARGET_ID, 1));
        CareRequest waiting = saveRequest();
        saveAssignment(waiting, TARGET_ID, 1);
        flushAndClear();

        Map<Long, SharingRequestInfo> result =
                reader.findAllByIds(List.of(accepted.getId(), waiting.getId(), 12345L));

        assertThat(result).containsOnlyKeys(accepted.getId(), waiting.getId());
        assertThat(result.get(accepted.getId()).assigneeId()).isEqualTo(TARGET_ID);
        assertThat(result.get(waiting.getId()).assigneeId()).isNull();
    }

    @Test
    void 빈_목록이면_빈_결과다() {
        assertThat(reader.findAllByIds(List.of())).isEmpty();
    }

    // === hasBeenAssigned (D #45) ===

    @Test
    void 지정된_적이_있으면_마감되고_수락하지_않았어도_true다() {
        CareRequest request = saveRequest();
        RequestAssignment first = RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(24));
        first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        first.close(AssignmentCloseReason.TARGET_CHANGED, NOW); // 과거 대상 (미수락, 마감)
        assignmentRepository.save(first);
        saveAssignment(request, NEXT_TARGET_ID, 2);              // 지금 대상
        flushAndClear();

        assertThat(reader.hasBeenAssigned(request.getId(), TARGET_ID)).isTrue();
        assertThat(reader.hasBeenAssigned(request.getId(), NEXT_TARGET_ID)).isTrue();
    }

    @Test
    void 요청자_무관한_사람_없는_요청은_false다() {
        CareRequest request = saveRequest();
        saveAssignment(request, TARGET_ID, 1);
        flushAndClear();

        assertThat(reader.hasBeenAssigned(request.getId(), REQUESTER_ID)).isFalse();
        assertThat(reader.hasBeenAssigned(request.getId(), STRANGER_ID)).isFalse();
        assertThat(reader.hasBeenAssigned(12345L, TARGET_ID)).isFalse();
    }

    // === isCurrentTarget (D 전달받은 정보 조회) ===

    @Test
    void 응답을_기다리는_지금_대상과_수락한_담당자는_true다() {
        CareRequest waiting = saveRequest();
        saveAssignment(waiting, TARGET_ID, 1);                      // 미응답
        CareRequest accepted = saveRequest();
        accept(accepted, saveAssignment(accepted, TARGET_ID, 1));  // 수락
        flushAndClear();

        assertThat(reader.isCurrentTarget(waiting.getId(), TARGET_ID)).isTrue();
        assertThat(reader.isCurrentTarget(accepted.getId(), TARGET_ID)).isTrue();
    }

    @Test
    void 어려움으로_답했어도_아직_다른_사람에게_넘어가지_않았으면_true다() {
        CareRequest request = saveRequest();
        saveAssignment(request, TARGET_ID, 1).respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        flushAndClear();

        assertThat(reader.isCurrentTarget(request.getId(), TARGET_ID)).isTrue(); // 지정은 다시 부탁할 때 마감됨
    }

    @Test
    void 다른_사람에게_넘어간_예전_대상과_수락을_철회한_담당자는_false다() {
        CareRequest changed = saveRequest();
        RequestAssignment first = RequestAssignment.assign(changed, TARGET_ID, 1, NOW.plusHours(24));
        first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        first.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
        assignmentRepository.save(first);
        saveAssignment(changed, NEXT_TARGET_ID, 2);

        CareRequest withdrawn = saveRequest();
        RequestAssignment assignee = saveAssignment(withdrawn, TARGET_ID, 1);
        accept(withdrawn, assignee);
        assignee.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW);
        withdrawn.revertToRequested();
        flushAndClear();

        assertThat(reader.isCurrentTarget(changed.getId(), TARGET_ID)).isFalse();
        assertThat(reader.isCurrentTarget(changed.getId(), NEXT_TARGET_ID)).isTrue(); // 지금 대상
        assertThat(reader.isCurrentTarget(withdrawn.getId(), TARGET_ID)).isFalse();
    }

    @Test
    void 완료된_요청의_담당자는_true이고_취소된_요청은_false다() {
        CareRequest completed = saveRequest();
        accept(completed, saveAssignment(completed, TARGET_ID, 1));
        completed.start(NOW);
        completed.complete(NOW.plusHours(1));                       // 완료는 지정을 마감하지 않음

        CareRequest canceled = saveRequest();
        RequestAssignment target = saveAssignment(canceled, TARGET_ID, 1);
        target.close(AssignmentCloseReason.REQUEST_ENDED, NOW);      // 취소는 지정을 함께 마감
        canceled.cancel(CancelReason.REQUESTER_CANCELED, NOW);
        flushAndClear();

        assertThat(reader.isCurrentTarget(completed.getId(), TARGET_ID)).isTrue();
        assertThat(reader.isCurrentTarget(canceled.getId(), TARGET_ID)).isFalse();
    }

    @Test
    void 요청자_지정된_적_없는_사람_없는_요청은_false다() {
        CareRequest request = saveRequest();
        saveAssignment(request, TARGET_ID, 1);
        flushAndClear();

        assertThat(reader.isCurrentTarget(request.getId(), REQUESTER_ID)).isFalse();
        assertThat(reader.isCurrentTarget(request.getId(), STRANGER_ID)).isFalse();
        assertThat(reader.isCurrentTarget(12345L, TARGET_ID)).isFalse();
    }

    // === findLastAssignmentId (D #138 공개 항목 선택지) ===

    @Test
    void 지정_ID가_아니라_assignment_order가_가장_큰_지정을_돌려준다() {

        CareRequest request = saveRequest();

        // 두 번째로 부탁한 지정(order 2)을 먼저 저장해 ID가 더 작게 만듦 (ID 순서로는 통과하지 못하도록)
        RequestAssignment second = saveAssignment(request, NEXT_TARGET_ID, 2);
        RequestAssignment first = RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(24));

        first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        first.close(AssignmentCloseReason.TARGET_CHANGED, NOW); // 열린 지정은 요청당 1건이라 먼저 부탁한 지정은 마감

        assignmentRepository.save(first);
        flushAndClear();

        assertThat(first.getId()).isGreaterThan(second.getId()); // 준비 확인: ID는 첫 번째 지정이 더 큼
        assertThat(reader.findLastAssignmentId(request.getId())).contains(second.getId());
    }

    @Test
    void 마지막_지정이_마감됐어도_돌려준다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, TARGET_ID, 1);
        accept(request, assignment);
        assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW); // 수락 철회로 마감, 아직 다시 부탁하지 않음
        request.revertToRequested();
        flushAndClear();

        assertThat(reader.findLastAssignmentId(request.getId())).contains(assignment.getId());
    }

    @Test
    void 지정이_없거나_없는_요청이면_빈_Optional이다() {
        CareRequest request = saveRequest();
        flushAndClear();

        assertThat(reader.findLastAssignmentId(request.getId())).isEmpty();
        assertThat(reader.findLastAssignmentId(12345L)).isEmpty();
    }

    // === 준비 코드 ===

    private CareRequest saveRequest() {
        return careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
    }

    private RequestAssignment saveAssignment(CareRequest request, Long targetUserId, int order) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, order, NOW.plusHours(12)));
    }

    /** 수락: 지정 응답(가능) → 요청 상태 변경 순서 (서비스에서 할 순서와 같음) */
    private void accept(CareRequest request, RequestAssignment assignment) {
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW);
        request.accept();
    }

    private void assertNoAssignee(SharingRequestInfo info, CareRequestStatus expectedStatus) {
        assertThat(info.status()).isEqualTo(expectedStatus);
        assertThat(info.assignmentId()).isNull();
        assertThat(info.assigneeId()).isNull();
        assertThat(info.acceptedAt()).isNull();
    }

    /** DB에 반영하고 JPA 메모리를 비워서 조회가 진짜 DB를 거치게 합니다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}