package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 다시 부탁하기(#71)가 다른 작업과 동시에 들어와도 결과가 어긋나지 않는지 확인합니다.
 * 모든 작업이 같은 요청 행을 먼저 잠그므로 차례로 처리됩니다. 서비스마다 진짜 커밋이 필요해 트랜잭션 밖에서 실행합니다.
 * 운영과 같이 "요청당 열린 지정 1건" 제약(OpenAssignmentConstraint)을 걸어 두고 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestFinder.class,
        RequestTargetEligibility.class,
        CareRequestReassignService.class,
        CareRequestResponseService.class
})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CareRequestReassignConcurrencyTest {
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

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;
    private static final Long JIHO = 40L;

    @Autowired private CareRequestReassignService reassignService;
    @Autowired private CareRequestResponseService responseService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private SituationParticipantReader situationReader;
    @MockitoBean private SafetyInformationSharingReader sharingReader;

    @BeforeEach
    void setUp() {
        OpenAssignmentConstraint.createIfAbsent(jdbcTemplate); // 테스트 트랜잭션이 없어 여기서 DDL을 실행해도 됨
        when(situationReader.isActiveParticipant(eq(SITUATION_ID), anyLong())).thenReturn(true);
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, MINJAE, "동생"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, SEOYEON, "친구"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, JIHO, "이웃"));
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
        relationshipRepository.deleteAllInBatch();
    }

    @Test
    void 서로_다른_사람에게_동시에_다시_부탁하면_하나만_성공하고_나머지는_다시_부탁할_수_없다() throws Exception {
        Long requestId = unavailableRequest();

        List<String> results = runTogether(
                () -> reassignService.reassign(requestId, REQUESTER_ID, body(SEOYEON)),
                () -> reassignService.reassign(requestId, REQUESTER_ID, body(JIHO)));

        // 먼저 잠근 쪽이 새 지정을 만들면 요청은 응답 대기가 되어, 나중 쪽은 담당자 없음이 아님
        assertThat(results).containsExactlyInAnyOrder("SUCCESS", CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE.getCode());
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).extracting(RequestAssignment::getAssignmentOrder).containsExactly(1, 2);
        assertThat(assignments.get(0).getCloseReason()).isEqualTo(AssignmentCloseReason.TARGET_CHANGED);
        assertThat(assignments.get(1).isOpen()).isTrue();
    }

    @Test
    void 같은_사람에게_동시에_다시_부탁하면_하나만_성공한다() throws Exception {
        Long requestId = unavailableRequest();

        List<String> results = runTogether(
                () -> reassignService.reassign(requestId, REQUESTER_ID, body(SEOYEON)),
                () -> reassignService.reassign(requestId, REQUESTER_ID, body(SEOYEON)));

        // 나중 쪽은 이미 부탁한 사람(먼저 확인) 또는 담당자 없음이 아님 중 하나로 막힘
        assertThat(results).contains("SUCCESS");
        assertThat(results).filteredOn(r -> !r.equals("SUCCESS")).singleElement().isIn(
                CareRequestErrorCode.REQUEST_TARGET_ALREADY_ASSIGNED.getCode(),
                CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE.getCode());
        assertThat(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId)).hasSize(2);
    }

    @Test
    void 어려움에서_수락으로_바꾸는_응답과_다시_부탁하기가_겹치면_한쪽만_반영된다() throws Exception {
        Long requestId = unavailableRequest();

        List<String> results = runTogether(
                () -> responseService.respond(requestId, MINJAE, AssignmentResponseStatus.AVAILABLE),
                () -> reassignService.reassign(requestId, REQUESTER_ID, body(SEOYEON)));

        CareRequest request = careRequestRepository.findById(requestId).orElseThrow();
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        if (results.get(0).equals("SUCCESS")) {
            // 수락 먼저: 요청은 수락됨, 다시 부탁하기는 409
            assertThat(results.get(1)).isEqualTo(CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE.getCode());
            assertThat(request.getStatus()).isEqualTo(CareRequestStatus.ACCEPTED);
            assertThat(assignments).hasSize(1);
        } else {
            // 다시 부탁하기 먼저: 이전 지정이 마감되어 수락은 409 (다른 분께 넘어감)
            assertThat(results).containsExactly(CareRequestErrorCode.REQUEST_ASSIGNMENT_CLOSED.getCode(), "SUCCESS");
            assertThat(request.getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(assignments).hasSize(2);
        }
    }

    // === 준비 코드 ===

    /** 민재가 어려움으로 답해 담당자 없음인 요청 (커밋됨) */
    private Long unavailableRequest() {
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                HelpType.HOSPITAL, null, "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
        RequestAssignment assignment = RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12));
        assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(1));
        assignmentRepository.save(assignment);
        return request.getId();
    }

    private ReassignRequest body(Long targetUserId) {
        return new ReassignRequest(targetUserId, null, List.of());
    }

    /** 두 작업을 동시에 출발시키고, 각각 "SUCCESS" 또는 에러 코드를 순서대로 돌려줌 */
    private List<String> runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = executor.submit(gate(ready, start, first));
            Future<String> b = executor.submit(gate(ready, start, second));
            ready.await();
            start.countDown();
            return List.of(a.get(), b.get());
        } finally {
            executor.shutdown();
        }
    }

    private Callable<String> gate(CountDownLatch ready, CountDownLatch start, Runnable action) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                action.run();
                return "SUCCESS";
            } catch (BusinessException e) {
                return e.getErrorCode().getCode();
            }
        };
    }
}