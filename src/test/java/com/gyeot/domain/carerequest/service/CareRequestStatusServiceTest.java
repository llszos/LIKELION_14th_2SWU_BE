package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.CancelResponse;
import com.gyeot.domain.carerequest.dto.response.RequestStatusResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestEvent;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 돌봄 시작, 완료, 요청 취소의 규칙을 실제 MySQL로 확인합니다.
 * 상태 변경, 지정 처리(ERD 9.1), 수정안 무효화, 이력, 이벤트, 역할별 403, 상태별 409를 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        CareRequestLifecycle.class,
        CareRequestStatusService.class
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
public class CareRequestStatusServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long REQUESTER_ID = 10L;
    private static final Long ASSIGNEE_ID = 20L;
    private static final Long OTHER_TARGET_ID = 30L;
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestStatusService service;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    // === 돌봄 시작 ====
    @Test
    void 담당자가_시작하면_진행_중이_되고_이벤트는_없다() {
        CareRequest request = acceptedRequest();

        RequestStatusResponse result = service.start(request.getId(), ASSIGNEE_ID);

        assertThat(result.status()).isEqualTo(CareRequestStatus.IN_PROGRESS);
        assertThat(request.getStartedAt()).isEqualTo(NOW);
        assertThat(eventTypes(request)).containsExactly(ChangeEventType.REQUEST_STATUS_CHANGED);
        assertThat(publishedEvents()).isEmpty(); // 시작은 다른 영역에 알릴 일이 없음
    }

    @Test
    void 시작하면_대기_중_수정안이_REQUEST_STARTED로_무효가_된다() {
        CareRequest request = acceptedRequest();
        RequestChangeProposal proposal = saveProposal(request);

        service.start(request.getId(), ASSIGNEE_ID);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.INVALIDATED);
        assertThat(history(request).getLast().getEventDetails()).isEqualTo(Map.of("reason", "REQUEST_STARTED"));
    }

    @Test
    void 이미_진행_중이면_409_ALREADY_STARTED다() {
        CareRequest request = acceptedRequest();
        request.start(NOW);

        assertError(() -> service.start(request.getId(), ASSIGNEE_ID), CareRequestErrorCode.REQUEST_ALREADY_STARTED);
    }

    @Test
    void 요청자와_수락_전_대상자는_시작할_수_없다() {
        CareRequest request = saveRequest();
        saveAssignment(request, ASSIGNEE_ID); // 수락 전

        assertError(() -> service.start(request.getId(), REQUESTER_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.start(request.getId(), ASSIGNEE_ID), CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 끝난_요청은_시작할_수_없다() {
        CareRequest request = acceptedRequest();
        service.cancel(request.getId(), REQUESTER_ID);

        assertError(() -> service.start(request.getId(), ASSIGNEE_ID), CareRequestErrorCode.REQUEST_ALREADY_ENDED);
    }

    // === 돌봄 완료 ===

    @Test
    void 담당자가_완료하면_지정은_마감되지_않고_CompletedEvent에_완료한_사람이_담긴다() {
        CareRequest request = inProgressRequest();
        RequestAssignment assignment = assignmentOf(request, ASSIGNEE_ID);

        RequestStatusResponse result = service.complete(request.getId(), ASSIGNEE_ID);

        assertThat(result.status()).isEqualTo(CareRequestStatus.COMPLETED);
        assertThat(assignment.isOpen()).isTrue(); // ERD 9.1: 완료는 지정을 마감하지 않음
        CareRequestCompletedEvent event = (CareRequestCompletedEvent) publishedEvents().getFirst();
        assertThat(event.completedByUserId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
    }

    @Test
    void 요청자도_완료할_수_있다() {
        CareRequest request = inProgressRequest();

        service.complete(request.getId(), REQUESTER_ID);

        CareRequestCompletedEvent event = (CareRequestCompletedEvent) publishedEvents().getFirst();
        assertThat(event.completedByUserId()).isEqualTo(REQUESTER_ID);
    }

    @Test
    void 시작_전이면_409_NOT_STARTED다() {
        CareRequest request = acceptedRequest();

        assertError(() -> service.complete(request.getId(), ASSIGNEE_ID), CareRequestErrorCode.REQUEST_NOT_STARTED);
        assertError(() -> service.complete(request.getId(), REQUESTER_ID), CareRequestErrorCode.REQUEST_NOT_STARTED);
    }

    @Test
    void 이미_완료됐으면_409_ALREADY_ENDED다() {
        CareRequest request = inProgressRequest();
        service.complete(request.getId(), ASSIGNEE_ID);

        assertError(() -> service.complete(request.getId(), REQUESTER_ID), CareRequestErrorCode.REQUEST_ALREADY_ENDED);
    }

    @Test
    void 담당자가_아닌_이전_대상은_완료할_수_없다() {
        CareRequest request = saveRequest();
        RequestAssignment previous = saveAssignment(request, OTHER_TARGET_ID);
        previous.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        previous.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
        RequestAssignment current = assignmentRepository.save(
                RequestAssignment.assign(request, ASSIGNEE_ID, 2, NOW.plusHours(12)));
        current.respond(AssignmentResponseStatus.AVAILABLE, NOW);
        request.accept();
        request.start(NOW);

        assertError(() -> service.complete(request.getId(), OTHER_TARGET_ID), CommonErrorCode.FORBIDDEN);
    }

    // === 요청 취소 ===
    @Test
    void 요청됨에서_취소하면_대상자_지정이_마감되고_CanceledEvent에_대상자가_담긴다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, ASSIGNEE_ID);

        CancelResponse result = service.cancel(request.getId(), REQUESTER_ID);

        assertThat(result.status()).isEqualTo(CareRequestStatus.CANCELED);
        assertThat(result.cancelReason()).isEqualTo(CancelReason.REQUESTER_CANCELED);
        assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
        // 지정 마감 이력(ASSIGNMENT_CLOSED)은 남기지 않고 상태 변경 이력만 (RequestChangeEvent 규칙)
        assertThat(eventTypes(request)).containsExactly(ChangeEventType.REQUEST_STATUS_CHANGED);
        CareRequestCanceledEvent event = (CareRequestCanceledEvent) publishedEvents().getFirst();
        assertThat(event.targetUserId()).isEqualTo(ASSIGNEE_ID);
        assertThat(publishedEvents()).hasSize(1); // 지정 마감 이벤트는 따로 없음
    }

    @Test
    void 진행_중인_요청도_취소할_수_있다() {
        CareRequest request = inProgressRequest();

        CancelResponse result = service.cancel(request.getId(), REQUESTER_ID);

        assertThat(result.status()).isEqualTo(CareRequestStatus.CANCELED);
        assertThat(assignmentOf(request, ASSIGNEE_ID).isOpen()).isFalse();
    }

    @Test
    void 대상이_없는_요청을_취소하면_이벤트의_대상자는_null이다() {
        CareRequest request = saveRequest();
        RequestAssignment assignment = saveAssignment(request, ASSIGNEE_ID);
        assignment.close(AssignmentCloseReason.PARTICIPANT_LEFT, NOW); // 대상자가 상황을 나가 지정이 이미 마감됨

        service.cancel(request.getId(), REQUESTER_ID);

        CareRequestCanceledEvent event = (CareRequestCanceledEvent) publishedEvents().getFirst();
        assertThat(event.assignmentId()).isNull();
        assertThat(event.targetUserId()).isNull();
        assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.PARTICIPANT_LEFT); // 원래 사유 유지
    }

    @Test
    void 취소하면_대기_중_수정안이_REQUEST_ENDED로_무효가_된다() {
        CareRequest request = acceptedRequest();
        RequestChangeProposal proposal = saveProposal(request);

        service.cancel(request.getId(), REQUESTER_ID);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.INVALIDATED);
        assertThat(history(request).getLast().getEventDetails()).isEqualTo(Map.of("reason", "REQUEST_ENDED"));
    }

    @Test
    void 대상자와_담당자는_취소할_수_없고_무관한_사람은_404다() {
        CareRequest request = acceptedRequest();

        assertError(() -> service.cancel(request.getId(), ASSIGNEE_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.cancel(request.getId(), STRANGER_ID), CareRequestErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    void 이미_끝난_요청은_취소할_수_없다() {
        CareRequest request = saveRequest();
        saveAssignment(request, ASSIGNEE_ID);
        service.cancel(request.getId(), REQUESTER_ID);

        assertError(() -> service.cancel(request.getId(), REQUESTER_ID), CareRequestErrorCode.REQUEST_ALREADY_ENDED);
    }

    // === 준비 코드 ===

    private CareRequest saveRequest() {
        return careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
    }

    private RequestAssignment saveAssignment(CareRequest request, Long targetUserId) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1, NOW.plusHours(12)));
    }

    /** 담당자(ASSIGNEE_ID)가 수락한 요청 */
    private CareRequest acceptedRequest() {
        CareRequest request = saveRequest();
        saveAssignment(request, ASSIGNEE_ID).respond(AssignmentResponseStatus.AVAILABLE, NOW);
        request.accept();
        return request;
    }

    /** 진행 중인 요청 */
    private CareRequest inProgressRequest() {
        CareRequest request = acceptedRequest();
        request.start(NOW);
        return request;
    }

    private RequestAssignment assignmentOf(CareRequest request, Long targetUserId) {
        return assignmentRepository.findByCareRequestIdAndTargetUserId(request.getId(), targetUserId).orElseThrow();
    }

    private RequestChangeProposal saveProposal(CareRequest request) {
        return proposalRepository.save(RequestChangeProposal.create(request, assignmentOf(request, ASSIGNEE_ID),
                Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW));
    }

    private List<RequestChangeEvent> history(CareRequest request) {
        entityManager.flush();
        return eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId());
    }

    private List<ChangeEventType> eventTypes(CareRequest request) {
        return history(request).stream().map(RequestChangeEvent::getEventType).toList();
    }

    private List<CareRequestEvent> publishedEvents() {
        return events.stream(CareRequestEvent.class).toList();
    }

    private void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}