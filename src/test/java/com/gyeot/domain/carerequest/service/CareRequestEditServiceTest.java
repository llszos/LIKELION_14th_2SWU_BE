package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.ProposalAcceptResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalRejectResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalWithdrawResponse;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse.Result;
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
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeProposedEvent;
import com.gyeot.domain.carerequest.event.CareRequestEvent;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 요청 수정, 수정안 철회, 재확인 수락·거절(#73)의 규칙을 실제 MySQL로 확인합니다.
 * D(공개 항목 검증·조회)와 B(상황 기간)는 목으로 대신합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        CareRequestLifecycle.class,
        RequestScheduleValidator.class,
        CareRequestEditService.class,
        ChangeProposalService.class,
        CareRequestEditServiceTest.FixedClockConfig.class // @Nested 안쪽 클래스는 바깥의 설정을 자동으로 못 찾아서 직접 등록
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
public class CareRequestEditServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);           // 11/12 09:00
    static final LocalDateTime ENDS_AT = STARTS_AT.plusHours(3);      // 11/12 12:00

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long ASSIGNEE_ID = 20L;
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestEditService editService;
    @Autowired private ChangeProposalService proposalService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    @MockitoBean private SafetyInformationSharingReader sharingReader;
    @MockitoBean private SituationParticipantReader situationReader;

    @BeforeEach
    void setUp() {
        // 상황 기간 11/9 ~ 11/20, 지금 공개 중인 항목 [12, 15]
        when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.of(new SituationSummary(
                REQUESTER_ID, NOW.toLocalDate().minusDays(1), NOW.toLocalDate().plusDays(10), "ACTIVE")));
        when(sharingReader.findSharingInformationIds(any())).thenReturn(List.of(12L, 15L));
    }

    // === 요청 수정: 수락 전 ===

    @Nested
    class 수락_전_수정 {

        @Test
        void 바로_반영되고_REQUEST_UPDATED_이력과_수정_확정_이벤트가_남는다() {
            CareRequest request = requestedRequest();

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(STARTS_AT.plusHours(1), ENDS_AT.plusHours(1), "△△병원", null));

            assertThat(result.result()).isEqualTo(Result.APPLIED);
            assertThat(result.proposalId()).isNull();
            assertThat(request.getStartsAt()).isEqualTo(STARTS_AT.plusHours(1));
            assertThat(request.getPlace()).isEqualTo("△△병원");
            assertThat(eventTypes(request)).containsExactly(ChangeEventType.REQUEST_UPDATED);
            assertThat(publishedEvents()).singleElement()
                    .isInstanceOfSatisfying(CareRequestChangeConfirmedEvent.class, event -> {
                        assertThat(event.proposalId()).isNull(); // 수락 전 직접 수정
                        assertThat(event.changedFields()).containsExactlyInAnyOrder(
                                ChangeableField.STARTS_AT, ChangeableField.ENDS_AT, ChangeableField.PLACE);
                        assertThat(event.informationIds()).isNull();
                    });
        }

        @Test
        void 시작을_앞당기면_응답_기한도_새_시작_시각까지로_줄어든다() {
            CareRequest request = requestedRequest();  // 응답 기한 NOW + 12시간
            LocalDateTime newStart = NOW.plusHours(5);

            editService.update(request.getId(), REQUESTER_ID, body(newStart, null, null, null));

            assertThat(assignmentOf(request).getResponseDeadline()).isEqualTo(newStart);
        }

        @Test
        void 공개_항목이_바뀌면_D_검증_뒤_변경_후_전체_목록을_이벤트로_넘긴다() {
            CareRequest request = requestedRequest();

            editService.update(request.getId(), REQUESTER_ID, body(null, null, null, List.of(12L, 20L)));

            verify(sharingReader).validateSelection(REQUESTER_ID, List.of(12L, 20L));
            assertThat(publishedEvents()).singleElement()
                    .isInstanceOfSatisfying(CareRequestChangeConfirmedEvent.class, event ->
                            assertThat(event.informationIds()).containsExactly(12L, 20L));
        }
    }

    // === 요청 수정: 수락 후 ===

    @Nested
    class 수락_후_수정 {

        @Test
        void 수정안이_만들어지고_요청은_그대로이며_재확인_요청_이벤트가_발행된다() {
            CareRequest request = acceptedRequest();

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(null, null, "△△병원", null));

            assertThat(result.result()).isEqualTo(Result.PROPOSED);
            RequestChangeProposal proposal = proposalRepository.findById(result.proposalId()).orElseThrow();
            assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
            assertThat(proposal.getChangedFields()).containsExactly(ChangeableField.PLACE);
            assertThat(result.proposalResponseDeadline()).isEqualTo(NOW.plusHours(24)); // 시작(이틀 뒤)보다 빠름
            assertThat(request.getPlace()).isEqualTo("○○병원");                          // 재확인 전에는 기존 내용
            assertThat(eventTypes(request)).containsExactly(ChangeEventType.RECONFIRMATION_REQUESTED);
            assertThat(publishedEvents()).singleElement().isInstanceOf(CareRequestChangeProposedEvent.class);
        }

        @Test
        void 재확인_대기_중_다시_수정하면_기존_수정안은_REPLACED_BY_NEW_PROPOSAL로_무효가_된다() {
            CareRequest request = acceptedRequest();
            Long first = editService.update(request.getId(), REQUESTER_ID, body(null, null, "△△병원", null)).proposalId();

            Long second = editService.update(request.getId(), REQUESTER_ID, body(null, null, "□□병원", null)).proposalId();

            assertThat(proposalRepository.findById(first).orElseThrow().getStatus()).isEqualTo(ProposalStatus.INVALIDATED);
            assertThat(proposalRepository.findById(second).orElseThrow().getStatus()).isEqualTo(ProposalStatus.PENDING);
            assertThat(invalidationReasons(request)).containsExactly("REPLACED_BY_NEW_PROPOSAL");
        }

        @Test
        void 대기_중_수정안을_원래_값으로_되돌리면_수정안이_철회된다() {
            CareRequest request = acceptedRequest();
            Long first = editService.update(request.getId(), REQUESTER_ID, body(null, null, "△△병원", null)).proposalId();

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(null, null, "○○병원", null));

            assertThat(result.result()).isEqualTo(Result.PROPOSAL_WITHDRAWN);
            assertThat(proposalRepository.findById(first).orElseThrow().getStatus()).isEqualTo(ProposalStatus.INVALIDATED);
            assertThat(invalidationReasons(request)).containsExactly("REQUESTER_WITHDRAWN");
        }

        @Test
        void 시작을_앞당기면_재확인_기한은_새_시작_시각까지다() {
            CareRequest request = acceptedRequest();
            LocalDateTime newStart = NOW.plusHours(3);

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(newStart, null, null, null));

            assertThat(result.proposalResponseDeadline()).isEqualTo(newStart);
        }
    }

    // === 요청 수정: 바뀐 필드와 규칙 ===

    @Nested
    class 수정_규칙 {

        @Test
        void 보낸_값이_모두_원래와_같고_대기_수정안이_없으면_REQUEST_NO_CHANGES다() {
            CareRequest request = requestedRequest();

            assertError(() -> editService.update(request.getId(), REQUESTER_ID,
                            body(STARTS_AT, ENDS_AT, "○○병원", List.of(15L, 12L))), // 공개 항목은 순서만 다름
                    CareRequestErrorCode.REQUEST_NO_CHANGES);
        }

        @Test
        void 시작을_1시간_이내로_바꾸면_REQUEST_START_TOO_SOON이다() {
            CareRequest request = requestedRequest();

            assertError(() -> editService.update(request.getId(), REQUESTER_ID,
                    body(NOW.plusMinutes(59), null, null, null)), CareRequestErrorCode.REQUEST_START_TOO_SOON);
        }

        @Test
        void 시작이_1시간_안_남아도_시작을_바꾸지_않으면_장소는_고칠_수_있다() {
            CareRequest request = requestedRequest(NOW.plusMinutes(40), NOW.plusHours(3), NOW.plusMinutes(30));

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(null, null, "△△병원", null));

            assertThat(result.result()).isEqualTo(Result.APPLIED);
        }

        @Test
        void 시작이_종료보다_늦거나_상황_기간_밖이면_REQUEST_SCHEDULE_INVALID다() {
            CareRequest request = requestedRequest();

            assertError(() -> editService.update(request.getId(), REQUESTER_ID,
                    body(null, STARTS_AT.minusHours(1), null, null)), CareRequestErrorCode.REQUEST_SCHEDULE_INVALID);
            assertError(() -> editService.update(request.getId(), REQUESTER_ID,
                    body(null, NOW.plusDays(11), null, null)), CareRequestErrorCode.REQUEST_SCHEDULE_INVALID); // 11/21
        }

        @Test
        void 상황_종료일_끝까지는_종료_시각으로_허용한다() {
            CareRequest request = requestedRequest();

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(null, NOW.toLocalDate().plusDays(11).atStartOfDay(), null, null)); // 11/21 00:00 = 11/20 끝

            assertThat(result.result()).isEqualTo(Result.APPLIED);
        }

        @Test
        void 장소가_100자를_넘으면_REQUEST_PLACE_TOO_LONG이고_앞뒤_공백은_세지_않는다() {
            CareRequest request = requestedRequest();

            assertError(() -> editService.update(request.getId(), REQUESTER_ID,
                    body(null, null, "가".repeat(101), null)), CareRequestErrorCode.REQUEST_PLACE_TOO_LONG);

            UpdateCareRequestResponse result = editService.update(request.getId(), REQUESTER_ID,
                    body(null, null, "  " + "가".repeat(100) + "  ", null)); // 앞뒤 공백을 빼면 정확히 100자
            assertThat(result.result()).isEqualTo(Result.APPLIED);
            assertThat(request.getPlace()).hasSize(100);
        }

        @Test
        void 공개_항목을_보내지_않으면_D_검증을_부르지_않는다() {
            CareRequest request = requestedRequest();

            editService.update(request.getId(), REQUESTER_ID, body(null, null, "△△병원", null));

            verify(sharingReader, never()).validateSelection(any(), anyList());
        }
    }

    // === 요청 수정: 상태와 권한 ===

    @Nested
    class 수정_상태와_권한 {

        @Test
        void 진행_중이거나_시작_시각이_지났으면_REQUEST_NOT_EDITABLE이다() {
            CareRequest inProgress = acceptedRequest();
            inProgress.start(NOW);
            CareRequest started = requestedRequest(NOW.minusMinutes(1), NOW.plusHours(3), NOW.minusMinutes(1));

            assertError(() -> editService.update(inProgress.getId(), REQUESTER_ID, body(null, null, "△△병원", null)),
                    CareRequestErrorCode.REQUEST_NOT_EDITABLE);
            assertError(() -> editService.update(started.getId(), REQUESTER_ID, body(null, null, "△△병원", null)),
                    CareRequestErrorCode.REQUEST_NOT_EDITABLE);
        }

        @Test
        void 끝난_요청이면_REQUEST_ALREADY_ENDED다() {
            CareRequest request = requestedRequest();
            request.cancel(CancelReason.REQUESTER_CANCELED, NOW);

            assertError(() -> editService.update(request.getId(), REQUESTER_ID, body(null, null, "△△병원", null)),
                    CareRequestErrorCode.REQUEST_ALREADY_ENDED);
        }

        @Test
        void 담당자는_403_관련_없는_사람은_404다() {
            CareRequest request = acceptedRequest();

            assertError(() -> editService.update(request.getId(), ASSIGNEE_ID, body(null, null, "△△병원", null)),
                    CommonErrorCode.FORBIDDEN);
            assertError(() -> editService.update(request.getId(), STRANGER_ID, body(null, null, "△△병원", null)),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
        }
    }

    // === 수정안 철회 ===

    @Nested
    class 수정안_철회 {

        @Test
        void 요청자가_철회하면_REQUESTER_WITHDRAWN으로_무효가_되고_요청은_그대로다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);

            ProposalWithdrawResponse result = proposalService.withdraw(request.getId(), proposal.getId(), REQUESTER_ID);

            assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.INVALIDATED);
            assertThat(invalidationReasons(request)).containsExactly("REQUESTER_WITHDRAWN");
            assertThat(request.getStatus()).isEqualTo(CareRequestStatus.ACCEPTED);
            assertThat(publishedEvents()).isEmpty();
        }

        @Test
        void 담당자가_철회하면_403_이미_처리된_수정안이면_409_다른_요청의_수정안이면_404다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);
            CareRequest other = acceptedRequest();

            assertError(() -> proposalService.withdraw(request.getId(), proposal.getId(), ASSIGNEE_ID),
                    CommonErrorCode.FORBIDDEN);
            assertError(() -> proposalService.withdraw(other.getId(), proposal.getId(), REQUESTER_ID),
                    CareRequestErrorCode.REQUEST_PROPOSAL_NOT_FOUND);

            proposal.invalidate();
            assertError(() -> proposalService.withdraw(request.getId(), proposal.getId(), REQUESTER_ID),
                    CareRequestErrorCode.REQUEST_PROPOSAL_NOT_PENDING);
        }
    }

    // === 재확인 수락·거절 ===

    @Nested
    class 재확인 {

        @Test
        void 담당자가_수락하면_수정이_반영되고_수정_확정_이벤트가_발행된다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);

            ProposalAcceptResponse result = proposalService.accept(request.getId(), proposal.getId(), ASSIGNEE_ID);

            assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.ACCEPTED);
            assertThat(result.status()).isEqualTo(CareRequestStatus.ACCEPTED);
            assertThat(request.getPlace()).isEqualTo("△△병원");
            assertThat(eventTypes(request)).containsExactly(ChangeEventType.RECONFIRMATION_ACCEPTED);
            assertThat(publishedEvents()).singleElement()
                    .isInstanceOfSatisfying(CareRequestChangeConfirmedEvent.class, event ->
                            assertThat(event.proposalId()).isEqualTo(proposal.getId()));
        }

        @Test
        void 담당자가_거절하면_요청이_RECONFIRMATION_REJECTED로_취소되고_지정이_마감된다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);

            ProposalRejectResponse result = proposalService.reject(request.getId(), proposal.getId(), ASSIGNEE_ID);

            assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.REJECTED);
            assertThat(result.status()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(result.cancelReason()).isEqualTo(CancelReason.RECONFIRMATION_REJECTED);
            assertThat(assignmentOf(request).getCloseReason()).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
            assertThat(eventTypes(request)).containsExactly(
                    ChangeEventType.RECONFIRMATION_REJECTED, ChangeEventType.REQUEST_STATUS_CHANGED);
            assertThat(publishedEvents()).singleElement().isInstanceOf(CareRequestCanceledEvent.class);
        }

        @Test
        void 재확인_기한이_지났으면_스케줄러_처리_전이어도_REQUEST_PROPOSAL_EXPIRED다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal expired = proposalAt(request, NOW.minusHours(25)); // 기한 NOW - 1시간, 아직 PENDING

            // 재확인 기한은 항상 시작 시각 이전이라, 시작 시각이 지난 경우도 이 경우에 포함됨
            assertError(() -> proposalService.accept(request.getId(), expired.getId(), ASSIGNEE_ID),
                    CareRequestErrorCode.REQUEST_PROPOSAL_EXPIRED);
            assertError(() -> proposalService.reject(request.getId(), expired.getId(), ASSIGNEE_ID),
                    CareRequestErrorCode.REQUEST_PROPOSAL_EXPIRED);
        }

        @Test
        void 요청자가_다시_수정해_무효가_된_수정안을_수락하면_REQUEST_PROPOSAL_NOT_PENDING이다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);
            editService.update(request.getId(), REQUESTER_ID, body(null, null, "□□병원", null)); // 새 수정안으로 교체

            assertError(() -> proposalService.accept(request.getId(), proposal.getId(), ASSIGNEE_ID),
                    CareRequestErrorCode.REQUEST_PROPOSAL_NOT_PENDING);
        }

        @Test
        void 요청자는_403_관련_없는_사람은_404다() {
            CareRequest request = acceptedRequest();
            RequestChangeProposal proposal = pendingProposal(request);

            assertError(() -> proposalService.accept(request.getId(), proposal.getId(), REQUESTER_ID),
                    CommonErrorCode.FORBIDDEN);
            assertError(() -> proposalService.accept(request.getId(), proposal.getId(), STRANGER_ID),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
        }
    }

    // === 준비 코드 ===

    private UpdateCareRequestRequest body(LocalDateTime startsAt, LocalDateTime endsAt, String place,
                                          List<Long> informationIds) {
        return new UpdateCareRequestRequest(startsAt, endsAt, place, informationIds);
    }

    private CareRequest requestedRequest() {
        return requestedRequest(STARTS_AT, ENDS_AT, NOW.plusHours(12));
    }

    private CareRequest requestedRequest(LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime deadline) {
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", startsAt, endsAt, null));
        assignmentRepository.save(RequestAssignment.assign(request, ASSIGNEE_ID, 1, deadline));
        return request;
    }

    private CareRequest acceptedRequest() {
        return acceptedRequest(STARTS_AT);
    }

    private CareRequest acceptedRequest(LocalDateTime startsAt) {
        CareRequest request = requestedRequest(startsAt, startsAt.plusHours(3), startsAt.minusMinutes(30));
        assignmentOf(request).respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(2));
        request.accept();
        return request;
    }

    /** 장소만 바꾸는 대기 중 수정안 (만든 시각 = NOW - 1시간) */
    private RequestChangeProposal pendingProposal(CareRequest request) {
        return proposalAt(request, NOW.minusHours(1));
    }

    private RequestChangeProposal proposalAt(CareRequest request, LocalDateTime createdAt) {
        return proposalRepository.save(RequestChangeProposal.create(request, assignmentOf(request),
                Set.of(ChangeableField.PLACE), null, null, "△△병원", null, createdAt));
    }

    private RequestAssignment assignmentOf(CareRequest request) {
        return assignmentRepository.findByCareRequestIdAndTargetUserId(request.getId(), ASSIGNEE_ID).orElseThrow();
    }

    private List<RequestChangeEvent> history(CareRequest request) {
        entityManager.flush();
        return eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId());
    }

    private List<ChangeEventType> eventTypes(CareRequest request) {
        return history(request).stream()
                .map(RequestChangeEvent::getEventType)
                .filter(type -> type != ChangeEventType.RECONFIRMATION_INVALIDATED) // 무효 사유는 따로 확인
                .toList();
    }

    private List<Object> invalidationReasons(CareRequest request) {
        return history(request).stream()
                .filter(event -> event.getEventType() == ChangeEventType.RECONFIRMATION_INVALIDATED)
                .map(event -> event.getEventDetails().get("reason"))
                .toList();
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
