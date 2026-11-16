package com.gyeot.domain.carerequest.listener;

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
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestEvent;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.carerequest.service.CareRequestCleanupService;
import com.gyeot.domain.carerequest.service.CareRequestLifecycle;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다른 영역 이벤트(상황 종료, 참여 종료, 관계 해제, 회원 탈퇴) 수신 처리(#76)를 실제 MySQL로 확인합니다.
 * 이벤트를 실제로 발행해 리스너 → 정리 서비스 → 요청 상태 처리까지 한 번에 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestLifecycle.class,
        CareRequestCleanupService.class,
        CareRequestCleanupListener.class
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
class CareRequestCleanupListenerTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);

    private static final Long SITUATION_ID = 1L;
    private static final Long OTHER_SITUATION_ID = 2L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    // === 상황 종료 ===

    @Nested
    class 상황_종료 {

        @Test
        void 끝나지_않은_요청을_모두_상황_종료로_취소하고_지정을_마감한다() {
            CareRequest waiting = request(SITUATION_ID);
            assign(waiting, MINJAE);
            CareRequest accepted = request(SITUATION_ID);
            accept(accepted, SEOYEON);
            CareRequest inProgress = request(SITUATION_ID);
            accept(inProgress, MINJAE);
            inProgress.start(NOW.minusMinutes(5));
            CareRequest completed = request(SITUATION_ID);
            accept(completed, MINJAE);
            completed.start(NOW.minusHours(2));
            completed.complete(NOW.minusHours(1));
            CareRequest otherSituation = request(OTHER_SITUATION_ID);
            assign(otherSituation, MINJAE);
            flushAndClear();

            publisher.publishEvent(new SituationEndedEvent(SITUATION_ID, NOW));
            flushAndClear();

            for (CareRequest request : List.of(waiting, accepted, inProgress)) {
                CareRequest reloaded = reload(request);
                assertThat(reloaded.getStatus()).isEqualTo(CareRequestStatus.CANCELED);
                assertThat(reloaded.getCancelReason()).isEqualTo(CancelReason.SITUATION_ENDED);
                assertThat(reloaded.getCanceledAt()).isEqualTo(NOW);
                assertThat(onlyAssignment(request).getCloseReason()).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
            }
            assertThat(reload(completed).getStatus()).isEqualTo(CareRequestStatus.COMPLETED);
            assertThat(reload(otherSituation).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(events.stream(CareRequestCanceledEvent.class)).hasSize(3); // D가 권한 회수
        }

        @Test
        void 대기_중인_수정안도_무효가_된다() {
            CareRequest request = request(SITUATION_ID);
            RequestAssignment assignee = accept(request, MINJAE);
            RequestChangeProposal proposal = proposal(request, assignee);
            flushAndClear();

            publisher.publishEvent(new SituationEndedEvent(SITUATION_ID, NOW));
            flushAndClear();

            assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.INVALIDATED);
        }

        @Test
        void 같은_이벤트를_두_번_받아도_두_번째는_아무것도_바꾸지_않는다() {
            CareRequest request = request(SITUATION_ID);
            assign(request, MINJAE);
            flushAndClear();

            publisher.publishEvent(new SituationEndedEvent(SITUATION_ID, NOW));
            flushAndClear();
            long historyCount = eventRepository.count();
            publisher.publishEvent(new SituationEndedEvent(SITUATION_ID, NOW.plusMinutes(1)));
            flushAndClear();

            assertThat(reload(request).getCanceledAt()).isEqualTo(NOW);
            assertThat(eventRepository.count()).isEqualTo(historyCount);
            assertThat(events.stream(CareRequestCanceledEvent.class)).hasSize(1);
        }
    }

    // === 참여 종료 ===

    @Nested
    class 참여_종료 {

        @Test
        void 수락_전_대상자가_나가면_지정만_마감하고_요청은_담당자_없음이다() {
            CareRequest request = request(SITUATION_ID);
            RequestAssignment assignment = assign(request, MINJAE);
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();

            assertThat(reload(request).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            RequestAssignment closed = onlyAssignment(request);
            assertThat(closed.getCloseReason()).isEqualTo(AssignmentCloseReason.PARTICIPANT_LEFT);
            assertThat(closed.getClosedAt()).isEqualTo(NOW);
            assertThat(closed.currentNoAssigneeReason(NOW)).isEqualTo(NoAssigneeReason.PARTICIPANT_LEFT);
            assertThat(history(request)).containsExactly(ChangeEventType.ASSIGNMENT_CLOSED);
            assertThat(events.stream(CareRequestAssignmentClosedEvent.class)).singleElement()
                    .satisfies(e -> {
                        assertThat(e.assignmentId()).isEqualTo(assignment.getId());
                        assertThat(e.closeReason()).isEqualTo(AssignmentCloseReason.PARTICIPANT_LEFT);
                    });
        }

        @Test
        void 시작_전_담당자가_나가면_지정을_마감하고_요청을_요청됨으로_되돌리고_수정안을_무효로_한다() {
            CareRequest request = request(SITUATION_ID);
            RequestAssignment assignee = accept(request, MINJAE);
            RequestChangeProposal proposal = proposal(request, assignee);
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();

            assertThat(reload(request).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(onlyAssignment(request).getCloseReason()).isEqualTo(AssignmentCloseReason.PARTICIPANT_LEFT);
            assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.INVALIDATED);
            assertThat(history(request)).containsExactly(ChangeEventType.ASSIGNMENT_CLOSED,
                    ChangeEventType.REQUEST_STATUS_CHANGED, ChangeEventType.RECONFIRMATION_INVALIDATED);
            assertThat(events.stream(CareRequestAssignmentClosedEvent.class)).hasSize(1);
            assertThat(events.stream(CareRequestCanceledEvent.class)).isEmpty();
        }

        @Test
        void 진행_중_담당자가_나가면_요청을_담당자_이탈로_취소한다() {
            CareRequest request = request(SITUATION_ID);
            accept(request, MINJAE);
            request.start(NOW.minusMinutes(5));
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();

            CareRequest reloaded = reload(request);
            assertThat(reloaded.getStatus()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(reloaded.getCancelReason()).isEqualTo(CancelReason.ASSIGNEE_LEFT);
            assertThat(onlyAssignment(request).getCloseReason()).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
            assertThat(events.stream(CareRequestCanceledEvent.class)).hasSize(1);
            assertThat(events.stream(CareRequestAssignmentClosedEvent.class)).isEmpty();
        }

        @Test
        void 관계_해제로_제외되면_관계_해제_사유로_정리한다() {
            CareRequest waiting = request(SITUATION_ID);
            assign(waiting, MINJAE);
            CareRequest inProgress = request(SITUATION_ID);
            accept(inProgress, MINJAE);
            inProgress.start(NOW.minusMinutes(5));
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.RELATIONSHIP_ENDED);
            flushAndClear();

            assertThat(onlyAssignment(waiting).getCloseReason()).isEqualTo(AssignmentCloseReason.RELATIONSHIP_ENDED);
            assertThat(reload(inProgress).getCancelReason()).isEqualTo(CancelReason.RELATIONSHIP_ENDED);
        }

        @Test
        void 나간_사람과_상관없는_요청과_지정은_그대로다() {
            CareRequest others = request(SITUATION_ID);
            assign(others, SEOYEON);                          // 다른 사람의 지정
            CareRequest passedOn = request(SITUATION_ID);     // 민재에게서 이미 넘어간 요청
            assignmentRepository.save(RequestAssignment.assign(passedOn, MINJAE, 1, NOW.plusHours(12)))
                    .close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusHours(1));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            assignmentRepository.save(RequestAssignment.assign(passedOn, SEOYEON, 2, NOW.plusHours(12)));
            CareRequest otherSituation = request(OTHER_SITUATION_ID); // 다른 상황의 민재 지정
            assign(otherSituation, MINJAE);
            CareRequest completed = request(SITUATION_ID);    // 완료된 요청의 민재 지정 (완료는 지정을 마감하지 않음)
            accept(completed, MINJAE);
            completed.start(NOW.minusHours(2));
            completed.complete(NOW.minusHours(1));
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();

            assertThat(onlyAssignment(others).isOpen()).isTrue();
            assertThat(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(passedOn.getId()))
                    .extracting(RequestAssignment::getCloseReason)
                    .containsExactly(AssignmentCloseReason.TARGET_CHANGED, null);
            assertThat(onlyAssignment(otherSituation).isOpen()).isTrue();
            assertThat(reload(completed).getStatus()).isEqualTo(CareRequestStatus.COMPLETED);
            assertThat(onlyAssignment(completed).isOpen()).isTrue();
            assertThat(events.stream(CareRequestEvent.class)).isEmpty();
        }

        @Test
        void 같은_이벤트를_두_번_받아도_두_번째는_아무것도_바꾸지_않는다() {
            CareRequest request = request(SITUATION_ID);
            accept(request, MINJAE);
            flushAndClear();

            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();
            leave(MINJAE, SituationParticipantRemovalReason.PARTICIPANT_LEFT);
            flushAndClear();

            assertThat(history(request)).containsExactly(ChangeEventType.ASSIGNMENT_CLOSED,
                    ChangeEventType.REQUEST_STATUS_CHANGED);
            assertThat(events.stream(CareRequestAssignmentClosedEvent.class)).hasSize(1);
        }
    }

    // === 관계 해제 ===

    @Nested
    class 관계_해제 {

        @Test
        void 상황_안의_일반_요청은_건드리지_않는다() {
            CareRequest request = request(SITUATION_ID);
            accept(request, MINJAE);
            flushAndClear();

            publisher.publishEvent(new RelationshipEndedEvent(1L, REQUESTER_ID, MINJAE, RelationshipType.MY_PERSON, NOW));
            flushAndClear();

            assertThat(reload(request).getStatus()).isEqualTo(CareRequestStatus.ACCEPTED);
            assertThat(events.stream(CareRequestEvent.class)).isEmpty();
        }

        @Test
        void 급한_요청은_관계_해제로_지정을_마감한다() {
            CareRequest urgent = urgentRequest(REQUESTER_ID);
            accept(urgent, MINJAE);
            flushAndClear();

            publisher.publishEvent(new RelationshipEndedEvent(1L, REQUESTER_ID, MINJAE, RelationshipType.MY_PERSON, NOW));
            flushAndClear();

            assertThat(reload(urgent).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(onlyAssignment(urgent).getCloseReason()).isEqualTo(AssignmentCloseReason.RELATIONSHIP_ENDED);
        }

        @Test
        void 나의_사람은_등록한_방향만_정리하고_보호자는_양쪽을_정리한다() {
            CareRequest fromRequester = urgentRequest(REQUESTER_ID); // 요청자 → 민재
            assign(fromRequester, MINJAE);
            CareRequest fromMinjae = urgentRequest(MINJAE);          // 민재 → 요청자
            assign(fromMinjae, REQUESTER_ID);
            flushAndClear();

            publisher.publishEvent(new RelationshipEndedEvent(1L, REQUESTER_ID, MINJAE, RelationshipType.MY_PERSON, NOW));
            flushAndClear();
            assertThat(onlyAssignment(fromRequester).isOpen()).isFalse();
            assertThat(onlyAssignment(fromMinjae).isOpen()).isTrue(); // 나의 사람은 반대 방향 관계가 따로 있음

            publisher.publishEvent(new RelationshipEndedEvent(2L, REQUESTER_ID, MINJAE, RelationshipType.GUARDIAN, NOW));
            flushAndClear();
            assertThat(onlyAssignment(fromMinjae).getCloseReason()).isEqualTo(AssignmentCloseReason.RELATIONSHIP_ENDED);
        }
    }

    // === 회원 탈퇴 ===

    @Nested
    class 회원_탈퇴 {

        @Test
        void 요청자가_탈퇴하면_끝나지_않은_내_요청을_탈퇴로_취소한다() {
            CareRequest waiting = request(SITUATION_ID);
            assign(waiting, MINJAE);
            CareRequest accepted = request(OTHER_SITUATION_ID);
            accept(accepted, SEOYEON);
            CareRequest completed = request(SITUATION_ID);
            accept(completed, MINJAE);
            completed.start(NOW.minusHours(2));
            completed.complete(NOW.minusHours(1));
            flushAndClear();

            publisher.publishEvent(new UserWithdrawnEvent(REQUESTER_ID, NOW));
            flushAndClear();

            assertThat(reload(waiting).getCancelReason()).isEqualTo(CancelReason.USER_WITHDRAWN);
            assertThat(reload(accepted).getCancelReason()).isEqualTo(CancelReason.USER_WITHDRAWN);
            assertThat(reload(completed).getStatus()).isEqualTo(CareRequestStatus.COMPLETED);
            assertThat(events.stream(CareRequestCanceledEvent.class)).hasSize(2);
        }

        @Test
        void 대상자_담당자가_탈퇴하면_역할에_따라_정리한다() {
            CareRequest waiting = request(SITUATION_ID);
            assign(waiting, MINJAE);
            CareRequest accepted = request(SITUATION_ID);
            accept(accepted, MINJAE);
            CareRequest inProgress = request(OTHER_SITUATION_ID);
            accept(inProgress, MINJAE);
            inProgress.start(NOW.minusMinutes(5));
            flushAndClear();

            publisher.publishEvent(new UserWithdrawnEvent(MINJAE, NOW));
            flushAndClear();

            assertThat(reload(waiting).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(onlyAssignment(waiting).getCloseReason()).isEqualTo(AssignmentCloseReason.USER_WITHDRAWN);
            assertThat(onlyAssignment(waiting).currentNoAssigneeReason(NOW)).isEqualTo(NoAssigneeReason.USER_WITHDRAWN);
            assertThat(reload(accepted).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(onlyAssignment(accepted).getCloseReason()).isEqualTo(AssignmentCloseReason.USER_WITHDRAWN);
            assertThat(reload(inProgress).getStatus()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(reload(inProgress).getCancelReason()).isEqualTo(CancelReason.USER_WITHDRAWN);
        }

        @Test
        void 같은_이벤트를_두_번_받아도_두_번째는_아무것도_바꾸지_않는다() {
            CareRequest mine = request(SITUATION_ID);
            assign(mine, MINJAE);
            CareRequest assigned = urgentRequest(SEOYEON);
            assign(assigned, REQUESTER_ID);
            flushAndClear();

            publisher.publishEvent(new UserWithdrawnEvent(REQUESTER_ID, NOW));
            flushAndClear();
            long historyCount = eventRepository.count();
            publisher.publishEvent(new UserWithdrawnEvent(REQUESTER_ID, NOW.plusMinutes(1)));
            flushAndClear();

            assertThat(eventRepository.count()).isEqualTo(historyCount);
            assertThat(events.stream(CareRequestEvent.class)).hasSize(2); // 취소 1 + 지정 마감 1
        }
    }

    // === 도우미 ===

    private void leave(Long userId, SituationParticipantRemovalReason reason) {
        publisher.publishEvent(new SituationParticipantRemovedEvent(SITUATION_ID, userId, reason, NOW));
    }

    private CareRequest request(Long situationId) {
        return careRequestRepository.save(CareRequest.createGeneral(situationId, REQUESTER_ID, HelpType.HOSPITAL,
                null, "○○병원", STARTS_AT, STARTS_AT.plusHours(3), null));
    }

    private CareRequest urgentRequest(Long requesterId) {
        return careRequestRepository.save(CareRequest.createUrgent(requesterId, HelpType.CHECK_IN, null, "집", null));
    }

    private RequestAssignment assign(CareRequest request, Long targetUserId) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1, NOW.plusHours(12)));
    }

    private RequestAssignment accept(CareRequest request, Long targetUserId) {
        RequestAssignment assignment = assign(request, targetUserId);
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(3));
        request.accept();
        return assignment;
    }

    private RequestChangeProposal proposal(CareRequest request, RequestAssignment assignee) {
        return proposalRepository.save(RequestChangeProposal.create(request, assignee, Set.of(ChangeableField.PLACE),
                null, null, "△△병원", null, NOW.minusHours(1)));
    }

    private CareRequest reload(CareRequest request) {
        return careRequestRepository.findById(request.getId()).orElseThrow();
    }

    private RequestAssignment onlyAssignment(CareRequest request) {
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(request.getId());
        assertThat(assignments).hasSize(1);
        return assignments.get(0);
    }

    private List<ChangeEventType> history(CareRequest request) {
        return eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId()).stream()
                .map(RequestChangeEvent::getEventType).toList();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
