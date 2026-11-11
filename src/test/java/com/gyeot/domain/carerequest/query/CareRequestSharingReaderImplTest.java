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
