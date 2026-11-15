package com.gyeot.domain.carerequest.listener;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.carerequest.service.CareRequestCleanupService;
import com.gyeot.domain.carerequest.service.CareRequestLifecycle;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트를 보낸 쪽(B·A) 트랜잭션 안에서 C가 처리할 때, 오래된 스냅샷 값으로 잘못 처리하지 않는지 확인합니다. (#76)
 *
 * MySQL 기본 격리 수준(REPEATABLE READ)에서 일반 SELECT는 트랜잭션의 첫 조회 시점 스냅샷을 봅니다.
 * B가 조회를 시작한 뒤 다른 트랜잭션이 요청을 바꾸고 커밋하면, B 트랜잭션 안의 일반 SELECT로는 바뀌기 전 값이 보입니다.
 * C는 요청 행과 지정을 잠금 읽기로 다시 읽어 마지막으로 커밋된 값으로 판단해야 합니다.
 * 트랜잭션 두 개를 실제로 커밋해야 해서 테스트 메서드를 트랜잭션 밖에서 실행합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestLifecycle.class,
        CareRequestCleanupService.class,
        CareRequestCleanupListener.class
})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CareRequestCleanupSnapshotTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 이벤트를_보낸_쪽이_조회를_시작한_뒤_다른_사람에게_넘어간_요청은_건드리지_않는다() {
        // 민재가 수락한 요청 (커밋)
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long requestId = tx.execute(status -> {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                    HelpType.HOSPITAL, null, "○○병원", NOW.plusDays(2), NOW.plusDays(2).plusHours(3), null));
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(3));
            request.accept();
            return request.getId();
        });

        // B 트랜잭션(참여 종료): 먼저 조회를 해서 스냅샷이 정해짐
        tx.executeWithoutResult(status -> {
            assertThat(assignmentRepository.findOpenRequestIdsInSituation(SITUATION_ID, MINJAE))
                    .containsExactly(requestId); // 이 시점의 스냅샷: 민재가 담당자

            // 그사이 다른 트랜잭션: 민재가 수락을 철회했고, 요청자가 서연에게 다시 부탁함 (커밋)
            TransactionTemplate otherTx = new TransactionTemplate(transactionManager);
            otherTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // 별도 트랜잭션·연결
            otherTx.executeWithoutResult(other -> {
                CareRequest request = careRequestRepository.findByIdForUpdate(requestId).orElseThrow();
                RequestAssignment minjae = assignmentRepository.findOpenByRequestIdForUpdate(requestId).orElseThrow();
                minjae.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusMinutes(10));
                request.revertToRequested();
                assignmentRepository.save(RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(12)));
            });
            // 별도 트랜잭션이라 이 시점에 커밋까지 끝남 (B 트랜잭션의 일반 SELECT로는 아직 민재가 담당자로 보임)

            // B가 이어서 참여 종료 이벤트 발행 → C가 같은 트랜잭션에서 처리
            publisher.publishEvent(new SituationParticipantRemovedEvent(SITUATION_ID, MINJAE,
                    SituationParticipantRemovalReason.PARTICIPANT_LEFT, NOW));
        });

        // 민재의 지정은 철회 사유 그대로, 서연의 지정은 열린 그대로, 요청은 요청됨 그대로
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).extracting(RequestAssignment::getTargetUserId).containsExactly(MINJAE, SEOYEON);
        assertThat(assignments.get(0).getCloseReason()).isEqualTo(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN);
        assertThat(assignments.get(0).getClosedAt()).isEqualTo(NOW.minusMinutes(10));
        assertThat(assignments.get(1).isOpen()).isTrue();
        assertThat(careRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(CareRequestStatus.REQUESTED);
        assertThat(eventRepository.count()).isZero(); // C는 아무 이력도 남기지 않음
    }
}
