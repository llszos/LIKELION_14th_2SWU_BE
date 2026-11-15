package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.RespondResponse;
import com.gyeot.domain.carerequest.dto.response.WithdrawResponse;
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
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestEvent;
import com.gyeot.domain.carerequest.event.CareRequestRespondedEvent;
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
 * 요청 응답·수락 철회의 규칙을 실제 MySQL로 확인합니다.
 * - 상태 변경, 이력(request_change_events), 발행한 이벤트, 에러 코드를 함께 확인합니다.
 * - 현재 시각은 NOW로 고정합니다.
 * - 동시 요청은 CareRequestResponseConcurrencyTest에서 따로 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        CareRequestResponseService.class
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents // 테스트 중 발행된 Spring 이벤트를 모아 둠 → ApplicationEvents로 확인
public class CareRequestResponseServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    /** 서비스가 주입받는 Clock을 NOW로 고정합니다. (테스트 안의 @TestConfiguration은 자동으로 등록됨) */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestResponseService service;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    // === 요청 응답 ===

    @Test
    void 도움_가능이면_수락되고_이력_2건과_AcceptedEvent만_남는다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);

        RespondResponse result = service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        assertThat(result.status()).isEqualTo(CareRequestStatus.ACCEPTED);
        assertThat(result.responseStatus()).isEqualTo(AssignmentResponseStatus.AVAILABLE);
        assertThat(assignment.getAcceptedAt()).isEqualTo(NOW);
        assertThat(eventTypes(request)).containsExactly(
                ChangeEventType.RESPONSE_CHANGED, ChangeEventType.REQUEST_STATUS_CHANGED);
        assertThat(publishedEvents()).singleElement().isInstanceOf(CareRequestAcceptedEvent.class);
    }

    @Test
    void 확인_후_답변이면_요청은_그대로이고_RespondedEvent가_나간다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);

        RespondResponse result = service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER);

        assertThat(result.status()).isEqualTo(CareRequestStatus.REQUESTED);
        assertThat(eventTypes(request)).containsExactly(ChangeEventType.RESPONSE_CHANGED);
        assertThat(publishedEvents()).singleElement().isInstanceOf(CareRequestRespondedEvent.class);
    }

    @Test
    void 확인_후_답변에서_수락으로_바꾸면_이전_응답이_이력에_남는다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER);

        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        List<RequestChangeEvent> history = history(request);
        assertThat(history.get(1).getEventDetails())
                .isEqualTo(Map.of("previous_status", "CHECK_LATER", "new_status", "AVAILABLE"));
    }

    @Test
    void 같은_응답을_다시_누르면_아무것도_바뀌지_않는다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER);
        events.clear();

        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER);

        assertThat(history(request)).hasSize(1); // 첫 응답 이력만
        assertThat(publishedEvents()).isEmpty();
    }

    @Test
    void 어려움에서_확인_후_답변으로는_바꿀_수_없다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.UNAVAILABLE);

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER),
                CareRequestErrorCode.REQUEST_RESPONSE_NOT_CHANGEABLE);
        assertThat(assignment.getResponseStatus()).isEqualTo(AssignmentResponseStatus.UNAVAILABLE); // 그대로
    }

    @Test
    void 어려움에서_수락으로는_바꿀_수_있다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.UNAVAILABLE);

        RespondResponse result = service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        assertThat(result.status()).isEqualTo(CareRequestStatus.ACCEPTED);
    }

    @Test
    void 확인_후_답변에서_어려움으로는_바꿀_수_있다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.CHECK_LATER);

        RespondResponse result = service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.UNAVAILABLE);

        assertThat(result.responseStatus()).isEqualTo(AssignmentResponseStatus.UNAVAILABLE);
    }


    @Test
    void 응답_기한이_지나도_시작_전이면_수락할_수_있다() {
        CareRequest request = saveRequest(NOW.plusHours(5));
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, TARGET_ID, 1, NOW.minusHours(1))); // 기한은 이미 지남

        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        assertThat(assignment.isCurrentAssignee()).isTrue();
    }

    @Test
    void 요청자가_응답하면_403이다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);

        assertError(() -> service.respond(request.getId(), REQUESTER_ID, AssignmentResponseStatus.AVAILABLE),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 관련_없는_사람은_404다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);

        assertError(() -> service.respond(request.getId(), STRANGER_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    void 이미_수락했으면_409_ALREADY_ACCEPTED다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.UNAVAILABLE),
                CareRequestErrorCode.REQUEST_ALREADY_ACCEPTED);
    }

    @Test
    void 수락을_철회한_사람은_다시_수락할_수_없다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);
        service.withdraw(request.getId(), TARGET_ID);

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_ASSIGNMENT_CLOSED);
    }

    @Test
    void 다른_분께_넘어간_이전_대상은_409_ASSIGNMENT_CLOSED다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);
        assignment.close(AssignmentCloseReason.TARGET_CHANGED, NOW);

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_ASSIGNMENT_CLOSED);
    }

    @Test
    void 끝난_요청은_409_ALREADY_ENDED다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);
        request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
        assignment.close(AssignmentCloseReason.REQUEST_ENDED, NOW);

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_ALREADY_ENDED);
    }

    @Test
    void 시작_시각이_지났으면_409_ALREADY_STARTED다() {
        CareRequest request = saveRequest(NOW.minusMinutes(1)); // 이미 시작 시각이 지남 (스케줄러 처리 전)
        assignmentRepository.save(RequestAssignment.assign(request, TARGET_ID, 1, NOW.minusHours(2)));

        assertError(() -> service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_ALREADY_STARTED);
    }

    // === 수락 철회 ===
    @Test
    void 철회하면_지정이_마감되고_요청됨으로_돌아간다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);
        events.clear();

        WithdrawResponse result = service.withdraw(request.getId(), TARGET_ID);

        assertThat(result.status()).isEqualTo(CareRequestStatus.REQUESTED);
        assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN);
        assertThat(eventTypes(request)).endsWith(
                ChangeEventType.ASSIGNMENT_CLOSED, ChangeEventType.REQUEST_STATUS_CHANGED);
        assertThat(publishedEvents()).singleElement().isInstanceOf(CareRequestAcceptanceWithdrawnEvent.class);
    }

    @Test
    void 대기_중_수정안이_있으면_함께_무효가_된다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        RequestAssignment assignment = saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);
        RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(
                request, assignment, Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW));

        service.withdraw(request.getId(), TARGET_ID);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.INVALIDATED);
        RequestChangeEvent last = history(request).getLast();
        assertThat(last.getEventType()).isEqualTo(ChangeEventType.RECONFIRMATION_INVALIDATED);
        assertThat(last.getEventDetails()).isEqualTo(Map.of("reason", "ASSIGNMENT_CLOSED"));
    }

    @Test
    void 진행_중이면_409_NOT_WITHDRAWABLE이다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);
        request.start(NOW);

        assertError(() -> service.withdraw(request.getId(), TARGET_ID),
                CareRequestErrorCode.REQUEST_NOT_WITHDRAWABLE);
    }

    @Test
    void 시작_시각이_지났으면_스케줄러_처리_전이어도_409_NOT_WITHDRAWABLE이다() {
        // 1분 전에 시작했어야 하는데 아직 ACCEPTED인 요청 (3시간 전에 수락함)
        CareRequest startedRequest = saveRequest(NOW.minusMinutes(1));
        RequestAssignment startedAssignment = assignmentRepository.save(
                RequestAssignment.assign(startedRequest, TARGET_ID, 1, NOW.minusHours(2)));
        startedAssignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(3));
        startedRequest.accept();

        assertError(() -> service.withdraw(startedRequest.getId(), TARGET_ID),
                CareRequestErrorCode.REQUEST_NOT_WITHDRAWABLE);
    }

    @Test
    void 수락하지_않은_대상자와_요청자는_403이다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request); // 미응답

        assertError(() -> service.withdraw(request.getId(), TARGET_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.withdraw(request.getId(), REQUESTER_ID), CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 완료된_요청은_409_ALREADY_ENDED다() {
        CareRequest request = saveRequest(NOW.plusDays(1));
        saveAssignment(request);
        service.respond(request.getId(), TARGET_ID, AssignmentResponseStatus.AVAILABLE);
        request.start(NOW);
        request.complete(NOW);

        assertError(() -> service.withdraw(request.getId(), TARGET_ID),
                CareRequestErrorCode.REQUEST_ALREADY_ENDED);
    }

    // === 준비 코드 ===

    private CareRequest saveRequest(LocalDateTime startsAt) {
        return careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", startsAt, startsAt.plusHours(3), null));
    }

    private RequestAssignment saveAssignment(CareRequest request) {
        return assignmentRepository.save(RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(12)));
    }

    /** DB에 반영한 뒤 이력을 순서대로 조회 */
    private List<RequestChangeEvent> history(CareRequest request) {
        entityManager.flush();
        return eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId());
    }

    private List<ChangeEventType> eventTypes(CareRequest request) {
        return history(request).stream().map(RequestChangeEvent::getEventType).toList();
    }

    /** 이 테스트에서 발행된 C 이벤트만 */
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
