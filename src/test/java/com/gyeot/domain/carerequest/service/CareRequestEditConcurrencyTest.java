package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 수정과 재확인이 동시에 들어와도 대기 수정안이 한 건만 남는지 확인합니다. (#73)
 *
 * - 요청 수정, 재확인 수락은 모두 같은 요청 행을 먼저 잠그므로 차례로 처리됩니다. 가능한 순서별 결과를 모두 허용하고,
 *   어느 순서든 최종 상태가 맞는지 확인합니다.
 * - "요청당 대기 수정안 1건" 제약(PendingProposalConstraint)도 걸어 두고 확인합니다. 순서가 꼬이면 제약 위반으로 실패합니다.
 * - 서비스마다 진짜 커밋이 필요해 테스트 트랜잭션 없이 실행하고, 끝나면 테이블을 비웁니다. (CareRequestStatusConcurrencyTest와 같은 방식)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestFinder.class,
        CareRequestLifecycle.class,
        RequestScheduleValidator.class,
        CareRequestEditService.class,
        ChangeProposalService.class
})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CareRequestEditConcurrencyTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);

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
    private static final String SUCCESS = "SUCCESS";

    @Autowired private CareRequestEditService editService;
    @Autowired private ChangeProposalService proposalService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 장소만 바꾸는 수정이라 D·B 조회는 실제로 쓰이지 않음 (서비스 생성에만 필요)
    @MockitoBean private SafetyInformationSharingReader sharingReader;
    @MockitoBean private SituationParticipantReader situationReader;

    @BeforeEach
    void setUp() {
        PendingProposalConstraint.createIfAbsent(jdbcTemplate); // 테스트 트랜잭션이 없어 여기서 DDL을 실행해도 됨
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 재확인_수락과_재수정이_동시에_들어와도_대기_수정안은_한_건만_남는다() throws Exception {
        Long requestId = acceptedRequest();
        Long firstProposalId = pendingProposal(requestId, "△△병원");

        List<String> results = runTogether(
                () -> proposalService.accept(requestId, firstProposalId, ASSIGNEE_ID),
                () -> editService.update(requestId, REQUESTER_ID, placeOnly("□□병원")));
        String acceptResult = results.get(0);
        String editResult = results.get(1);

        // 순서는 그때그때 다르지만 가능한 결과는 둘뿐
        // - 수락 먼저: 첫 수정안 반영(△△병원) → 재수정은 새 수정안(□□병원)으로 성공
        // - 재수정 먼저: 첫 수정안이 새 수정안으로 교체 → 수락은 이미 무효가 된 수정안이라 409
        assertThat(editResult).isEqualTo(SUCCESS);
        assertThat(acceptResult).isIn(SUCCESS, CareRequestErrorCode.REQUEST_PROPOSAL_NOT_PENDING.getCode());

        // 어느 순서든 대기 수정안은 재수정으로 만든 한 건뿐
        assertThat(proposalsOf(requestId, ProposalStatus.PENDING)).singleElement()
                .extracting(RequestChangeProposal::getProposedPlace).isEqualTo("□□병원");
        String place = careRequestRepository.findById(requestId).orElseThrow().getPlace();
        assertThat(place).isEqualTo(acceptResult.equals(SUCCESS) ? "△△병원" : "○○병원");
    }

    @Test
    void 요청자가_두_기기에서_동시에_수정해도_대기_수정안은_한_건만_남는다() throws Exception {
        Long requestId = acceptedRequest();

        List<String> results = runTogether(
                () -> editService.update(requestId, REQUESTER_ID, placeOnly("△△병원")),
                () -> editService.update(requestId, REQUESTER_ID, placeOnly("□□병원")));

        // 차례로 처리되어 둘 다 성공하고, 나중에 처리된 쪽이 앞의 수정안을 교체함
        assertThat(results).containsOnly(SUCCESS);
        assertThat(proposalsOf(requestId, ProposalStatus.PENDING)).hasSize(1);
        assertThat(proposalsOf(requestId, ProposalStatus.INVALIDATED)).hasSize(1);
    }

    // === 준비 코드 ===

    private Long acceptedRequest() {
        return inTx(() -> {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID,
                    HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", STARTS_AT, STARTS_AT.plusHours(3), null));
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, ASSIGNEE_ID, 1, STARTS_AT.minusHours(1)));
            assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(2));
            request.accept();
            return request.getId();
        });
    }

    private Long pendingProposal(Long requestId, String place) {
        return inTx(() -> {
            CareRequest request = careRequestRepository.findById(requestId).orElseThrow();
            RequestAssignment assignment = assignmentRepository
                    .findByCareRequestIdAndTargetUserId(requestId, ASSIGNEE_ID).orElseThrow();
            return proposalRepository.save(RequestChangeProposal.create(request, assignment,
                    Set.of(ChangeableField.PLACE), null, null, place, null, NOW.minusHours(1))).getId();
        });
    }

    private UpdateCareRequestRequest placeOnly(String place) {
        return new UpdateCareRequestRequest(null, null, place, null);
    }

    private List<RequestChangeProposal> proposalsOf(Long requestId, ProposalStatus status) {
        return inTx(() -> proposalRepository.findAll().stream()
                .filter(proposal -> proposal.getCareRequest().getId().equals(requestId))
                .filter(proposal -> proposal.getStatus() == status)
                .toList());
    }

    /** 두 작업을 같은 출발 신호로 동시에 실행하고, 각각의 결과("SUCCESS" 또는 업무 에러 코드)를 순서대로 돌려줍니다. */
    private List<String> runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(gate(ready, start, first));
            Future<String> secondResult = executor.submit(gate(ready, start, second));
            ready.await();
            start.countDown();
            return List.of(firstResult.get(), secondResult.get()); // 업무 예외가 아닌 예외(제약 위반 등)는 여기서 테스트 실패
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
                return SUCCESS;
            } catch (BusinessException e) {
                return e.getErrorCode().getCode();
            }
        };
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}