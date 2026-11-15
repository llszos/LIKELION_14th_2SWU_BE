package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.dto.response.ReassignResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 다시 부탁하기(#71)의 규칙을 실제 MySQL로 확인합니다.
 * A 관계는 실제 관계 행으로, B(참여자)와 D(공개 항목 검증)는 목으로 대신합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        RequestTargetEligibility.class,
        CareRequestReassignService.class,
        CareRequestReassignServiceTest.FixedClockConfig.class // @Nested 안쪽 클래스는 바깥의 설정을 자동으로 못 찾아서 직접 등록
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
public class CareRequestReassignServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);      // 11/12 09:00
    static final LocalDateTime ENDS_AT = STARTS_AT.plusHours(3);

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
    private static final Long MINJAE = 20L;   // 처음 부탁한 사람
    private static final Long SEOYEON = 30L;  // 다시 부탁할 사람 (나의 사람)
    private static final Long JIHO = 40L;     // 서로의 보호자
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestReassignService reassignService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    @MockitoBean private SituationParticipantReader situationReader;
    @MockitoBean private SafetyInformationSharingReader sharingReader;

    @BeforeEach
    void setUp() {
        when(situationReader.isActiveParticipant(eq(SITUATION_ID), anyLong())).thenReturn(true); // 모두 참여 중
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, MINJAE, "동생"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, SEOYEON, "친구"));
        relationshipRepository.save(Relationship.createGuardian(REQUESTER_ID, JIHO, 1));
        relationshipRepository.save(Relationship.createGuardian(JIHO, REQUESTER_ID, 1));
    }

    // === 성공 ===

    @Nested
    class 성공 {

        @Test
        void 어려움으로_답한_지정을_대상_변경으로_마감하고_새_지정을_만든다() {
            CareRequest request = request(STARTS_AT);
            RequestAssignment first = unavailable(request, MINJAE);
            flushAndClear();

            ReassignResponse response = reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));
            flushAndClear();

            List<RequestAssignment> assignments = assignmentRepository
                    .findAllByCareRequestIdOrderByAssignmentOrderAsc(request.getId());
            assertThat(assignments).hasSize(2);
            RequestAssignment closed = assignments.get(0);
            assertThat(closed.getId()).isEqualTo(first.getId());
            assertThat(closed.getCloseReason()).isEqualTo(AssignmentCloseReason.TARGET_CHANGED);
            assertThat(closed.getClosedAt()).isEqualTo(NOW);

            RequestAssignment next = assignments.get(1);
            assertThat(response.assignmentId()).isEqualTo(next.getId());
            assertThat(response.requestId()).isEqualTo(request.getId());
            assertThat(next.getTargetUserId()).isEqualTo(SEOYEON);
            assertThat(next.getAssignmentOrder()).isEqualTo(2);
            assertThat(next.isOpen()).isTrue();
            assertThat(next.getResponseStatus()).isNull();
            assertThat(next.getResponseDeadline()).isEqualTo(NOW.plusHours(24)).isEqualTo(response.responseDeadline());
            assertThat(next.getResponseDeadlineHours()).isNull();

            CareRequest reloaded = careRequestRepository.findById(request.getId()).orElseThrow();
            assertThat(RequestDisplayStatus.of(reloaded, next, NOW)).isEqualTo(RequestDisplayStatus.WAITING);
        }

        @Test
        void 대상_변경_이력과_이벤트를_남긴다() {
            CareRequest request = request(STARTS_AT);
            RequestAssignment first = unavailable(request, MINJAE);
            flushAndClear();

            ReassignResponse response = reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));

            List<RequestChangeEvent> history = eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId());
            assertThat(history).hasSize(1);
            assertThat(history.get(0).getEventType()).isEqualTo(ChangeEventType.TARGET_CHANGED);
            assertThat(history.get(0).getActorUserId()).isEqualTo(REQUESTER_ID);
            assertThat(history.get(0).getAssignment().getId()).isEqualTo(response.assignmentId());
            assertThat(((Number) history.get(0).getEventDetails().get("previous_assignment_id")).longValue())
                    .isEqualTo(first.getId());

            List<CareRequestTargetChangedEvent> published = events.stream(CareRequestTargetChangedEvent.class).toList();
            assertThat(published).hasSize(1);
            CareRequestTargetChangedEvent event = published.get(0);
            assertThat(event.previousAssignmentId()).isEqualTo(first.getId());
            assertThat(event.previousTargetUserId()).isEqualTo(MINJAE);
            assertThat(event.assignmentId()).isEqualTo(response.assignmentId());
            assertThat(event.targetUserId()).isEqualTo(SEOYEON);
            assertThat(event.informationIds()).containsExactly(12L, 14L);
            assertThat(event.occurredAt()).isEqualTo(NOW);
            verify(sharingReader).validateSelection(REQUESTER_ID, List.of(12L, 14L));
        }

        @Test
        void 응답_기한이_지난_지정도_대상_변경으로_마감한다() {
            CareRequest request = request(STARTS_AT);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(1)));
            flushAndClear();

            reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));
            flushAndClear();

            assertThat(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(request.getId()))
                    .extracting(RequestAssignment::getCloseReason)
                    .containsExactly(AssignmentCloseReason.TARGET_CHANGED, null);
        }

        @Test
        void 이미_마감된_지정은_마감_사유를_바꾸지_않는다() {
            CareRequest request = request(STARTS_AT);
            RequestAssignment first = accepted(request, MINJAE);
            first.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusHours(1)); // 수락 철회
            request.revertToRequested();
            flushAndClear();

            reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));
            flushAndClear();

            RequestAssignment reloaded = assignmentRepository.findById(first.getId()).orElseThrow();
            assertThat(reloaded.getCloseReason()).isEqualTo(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN);
            assertThat(reloaded.getClosedAt()).isEqualTo(NOW.minusHours(1));
            assertThat(eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId()))
                    .extracting(RequestChangeEvent::getEventType).containsExactly(ChangeEventType.TARGET_CHANGED);
        }

        @Test
        void 세_번째로_부탁하면_순서가_3이다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();
            reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));
            assignmentRepository.findByCareRequestIdAndTargetUserId(request.getId(), SEOYEON).orElseThrow()
                    .respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            flushAndClear();

            ReassignResponse third = reassignService.reassign(request.getId(), REQUESTER_ID, body(JIHO, null));

            assertThat(assignmentRepository.findById(third.assignmentId()).orElseThrow().getAssignmentOrder()).isEqualTo(3);
        }

        @Test
        void 고른_응답_기한을_저장하고_시작보다_늦으면_시작_시각으로_제한한다() {
            CareRequest request = request(NOW.plusHours(5));
            unavailable(request, MINJAE);
            flushAndClear();

            ReassignResponse response = reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, 48));

            RequestAssignment next = assignmentRepository.findById(response.assignmentId()).orElseThrow();
            assertThat(next.getResponseDeadline()).isEqualTo(NOW.plusHours(5));
            assertThat(next.getResponseDeadlineHours()).isEqualTo(48);
        }

        @Test
        void 새_요청과_달리_시작까지_1시간_미만이어도_보낼_수_있다() {
            CareRequest request = request(NOW.plusMinutes(30));
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(1)));
            flushAndClear();

            ReassignResponse response = reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null));

            assertThat(response.responseDeadline()).isEqualTo(NOW.plusMinutes(30)); // 시작 시각으로 고정
        }

        @Test
        void 서로의_보호자에게도_다시_부탁할_수_있다() {
            CareRequest request = request(NOW.plusDays(4)); // 72시간 기한이 시작 시각에 걸리지 않게
            unavailable(request, MINJAE);
            flushAndClear();

            assertThat(reassignService.reassign(request.getId(), REQUESTER_ID, body(JIHO, 72)).responseDeadline())
                    .isEqualTo(NOW.plusHours(72));
        }
    }

    // === 권한·상태 ===

    @Nested
    class 권한과_상태 {

        @Test
        void 관련_없는_사람이면_REQUEST_NOT_FOUND다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), STRANGER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
        }

        @Test
        void 대상자는_다시_부탁할_수_없어_FORBIDDEN이다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), MINJAE, body(SEOYEON, null)),
                    CommonErrorCode.FORBIDDEN);
        }

        @Test
        void 응답_대기_중이면_REQUEST_NOT_REASSIGNABLE이다() {
            CareRequest request = request(STARTS_AT);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE);
            assertThat(assignmentRepository.count()).isEqualTo(1);
        }

        @Test
        void 확인_후_답변이고_기한_전이면_응답_대기라_REQUEST_NOT_REASSIGNABLE이다() {
            CareRequest request = request(STARTS_AT);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)))
                    .respond(AssignmentResponseStatus.CHECK_LATER, NOW.minusHours(1));
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE);
        }

        @Test
        void 수락됐으면_REQUEST_NOT_REASSIGNABLE이다() {
            CareRequest request = request(STARTS_AT);
            accepted(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE);
        }

        @Test
        void 시작_시각이_지났으면_스케줄러_처리_전이어도_REQUEST_ALREADY_STARTED다() {
            CareRequest request = request(NOW.minusMinutes(1));
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(1)));
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_ALREADY_STARTED);
        }

        @Test
        void 진행_중이면_REQUEST_ALREADY_STARTED다() {
            CareRequest request = request(STARTS_AT);
            accepted(request, MINJAE);
            request.start(NOW);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_ALREADY_STARTED);
        }

        @Test
        void 취소된_요청이면_REQUEST_ALREADY_ENDED다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE).close(AssignmentCloseReason.REQUEST_ENDED, NOW);
            request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_ALREADY_ENDED);
        }
    }

    // === 입력·대상 ===

    @Nested
    class 입력과_대상 {

        @Test
        void 응답_기한이_범위_밖이면_REQUEST_DEADLINE_INVALID다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, 0)),
                    CareRequestErrorCode.REQUEST_DEADLINE_INVALID);
            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, 73)),
                    CareRequestErrorCode.REQUEST_DEADLINE_INVALID);
        }

        @Test
        void 이미_부탁했던_사람이면_REQUEST_TARGET_ALREADY_ASSIGNED다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(MINJAE, null)),
                    CareRequestErrorCode.REQUEST_TARGET_ALREADY_ASSIGNED);
        }

        @Test
        void 이전_대상이_상황에서_나갔어도_이미_부탁했던_사람으로_안내한다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();
            when(situationReader.isActiveParticipant(SITUATION_ID, MINJAE)).thenReturn(false);

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(MINJAE, null)),
                    CareRequestErrorCode.REQUEST_TARGET_ALREADY_ASSIGNED);
        }

        @Test
        void 본인에게는_다시_부탁할_수_없다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(REQUESTER_ID, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void 상황_참여자가_아니면_REQUEST_TARGET_INVALID다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();
            when(situationReader.isActiveParticipant(SITUATION_ID, SEOYEON)).thenReturn(false);

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void 관계가_없으면_REQUEST_TARGET_INVALID다() {
            CareRequest request = request(STARTS_AT);
            unavailable(request, MINJAE);
            flushAndClear();

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(STRANGER_ID, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void D가_공개_항목을_거부하면_그_에러를_돌려주고_아무것도_바꾸지_않는다() {
            CareRequest request = request(STARTS_AT);
            RequestAssignment first = unavailable(request, MINJAE);
            flushAndClear();
            doThrow(new BusinessException(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION))
                    .when(sharingReader).validateSelection(eq(REQUESTER_ID), anyList());

            assertError(() -> reassignService.reassign(request.getId(), REQUESTER_ID, body(SEOYEON, null)),
                    SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
            flushAndClear();
            assertThat(assignmentRepository.findById(first.getId()).orElseThrow().isOpen()).isTrue();
            assertThat(assignmentRepository.count()).isEqualTo(1);
            assertThat(events.stream(CareRequestTargetChangedEvent.class)).isEmpty();
        }
    }

    // === 도우미 ===

    private CareRequest request(LocalDateTime startsAt) {
        return careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID, HelpType.HOSPITAL,
                null, "○○병원", startsAt, startsAt.plusHours(3), null));
    }

    /** 첫 대상이 어려움으로 답함 (지정은 열린 채 담당자 없음) */
    private RequestAssignment unavailable(CareRequest request, Long targetUserId) {
        RequestAssignment assignment = assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1,
                min(NOW.plusHours(12), request.getStartsAt())));
        assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(1));
        return assignment;
    }

    private RequestAssignment accepted(CareRequest request, Long targetUserId) {
        RequestAssignment assignment = assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1,
                NOW.plusHours(12)));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(2));
        request.accept();
        return assignment;
    }

    private ReassignRequest body(Long targetUserId, Integer deadlineHours) {
        return new ReassignRequest(targetUserId, deadlineHours, List.of(12L, 14L));
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
