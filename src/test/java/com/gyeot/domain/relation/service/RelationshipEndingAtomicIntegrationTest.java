package com.gyeot.domain.relation.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.relation.entity.EmergencyContact;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.relation.exception.RelationshipErrorCode;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A 관계 해제부터 B 참여·C 요청·D 접근 권한 정리까지 같은 MySQL 트랜잭션으로 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RelationshipEndingAtomicIntegrationTest.FailureInjection.class)
class RelationshipEndingAtomicIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired private RelationshipService relationshipService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EmergencyContactRepository emergencyContactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SituationRepository situationRepository;
    @Autowired
    @Qualifier("situationParticipantRepository")
    private SituationParticipantRepository participantRepository;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeEventRepository changeEventRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FailureInjection failureInjection;
    @Autowired private Clock clock;

    private TransactionTemplate tx;
    private String triggerName;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        failureInjection.clear();
    }

    @AfterEach
    void cleanUp() {
        failureInjection.clear();
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName);
            triggerName = null;
        }
        // 이 클래스의 Testcontainers DB는 전용입니다. FK가 있는 자식부터 비워 새 트랜잭션으로 확인합니다.
        jdbcTemplate.update("DELETE FROM information_access_logs");
        jdbcTemplate.update("DELETE FROM request_information_permissions");
        jdbcTemplate.update("DELETE FROM request_change_events");
        jdbcTemplate.update("DELETE FROM request_assignments");
        jdbcTemplate.update("DELETE FROM care_requests");
        jdbcTemplate.update("DELETE FROM situation_participants");
        jdbcTemplate.update("DELETE FROM situations");
        jdbcTemplate.update("DELETE FROM emergency_contacts");
        jdbcTemplate.update("DELETE FROM relationships");
        jdbcTemplate.update("DELETE FROM safety_information");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void myPersonRelationshipEndCascadesSynchronouslyAcrossBcdWithoutDuplicateRecordsOnRetry() {
        Fixture fixture = createMyPersonFixture();

        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());

        assertCascadeApplied(fixture);
        assertThat(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(fixture.sourceUserId()))
                .extracting(EmergencyContact::getContactUserId)
                .doesNotContain(fixture.targetUserId());
        long logCount = accessLogsFor(fixture.chains());
        assertThat(logCount).isEqualTo(1);
        assertThatThrownBy(() -> relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RelationshipErrorCode.RELATIONSHIP_ALREADY_ENDED));
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(logCount);
    }

    @Test
    void redeliveringRelationshipEndedEventDoesNotDuplicateCascadeRecords() {
        Fixture fixture = createMyPersonFixture();

        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());
        assertCascadeApplied(fixture);
        long autoRevokeLogCount = accessLogsFor(fixture.chains());
        int requestHistoryCount = changeEventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(
                fixture.chains().getFirst().requestId()).size();
        var endedRelationship = relationshipRepository.findById(fixture.relationshipId()).orElseThrow();
        RelationshipEndedEvent replay = new RelationshipEndedEvent(fixture.relationshipId(), fixture.sourceUserId(),
                fixture.targetUserId(), endedRelationship.getRelationshipType(), endedRelationship.getEndedAt());

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(replay));

        assertCascadeApplied(fixture);
        assertThat(changeEventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(
                fixture.chains().getFirst().requestId())).hasSize(requestHistoryCount);
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(autoRevokeLogCount);
    }

    @Test
    void guardianRelationshipEndCleansBothUsersSituationsRequestsAndPermissions() {
        Fixture fixture = createGuardianFixture();

        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());

        assertThat(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatus(
                fixture.sourceUserId(), RelationshipType.GUARDIAN, RelationshipStatus.ENDED)).hasSize(1);
        assertThat(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatus(
                fixture.targetUserId(), RelationshipType.GUARDIAN, RelationshipStatus.ENDED)).hasSize(1);
        fixture.chains().forEach(this::assertChainApplied);
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(2);
    }

    @Test
    void failureAfterParticipantCleanupRollsBackAllDomainsAndAllowsRetry() {
        Fixture fixture = createMyPersonFixture();
        failureInjection.failAt(FailurePoint.B_AFTER_PARTICIPANT_REMOVED);

        assertThatThrownBy(() -> relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId()))
                .isInstanceOf(InjectedFailure.class);

        assertCascadeRolledBack(fixture);
        failureInjection.clear();
        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());
        assertCascadeApplied(fixture);
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(1);
    }

    @Test
    void failureAfterRequestCleanupBeforePermissionRevocationRollsBackAllDomainsAndAllowsRetry() {
        Fixture fixture = createMyPersonFixture();
        failureInjection.failAt(FailurePoint.C_AFTER_ASSIGNMENT_CLOSED);

        assertThatThrownBy(() -> relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId()))
                .isInstanceOf(InjectedFailure.class);

        assertCascadeRolledBack(fixture);
        failureInjection.clear();
        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());
        assertCascadeApplied(fixture);
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(1);
    }

    @Test
    void databaseFailureDuringPermissionRevocationRollsBackAllDomainsAndAllowsRetry() {
        Fixture fixture = createMyPersonFixture();
        triggerName = "fail_atomic_permission_revoke";
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_atomic_permission_revoke BEFORE UPDATE ON request_information_permissions
                FOR EACH ROW
                BEGIN
                    IF NEW.status = 'REVOKED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected D failure';
                    END IF;
                END
                """);

        assertThatThrownBy(() -> relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId()))
                .isInstanceOf(RuntimeException.class);

        assertCascadeRolledBack(fixture);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName);
        triggerName = null;
        relationshipService.endRelationship(fixture.sourceUserId(), fixture.relationshipId());
        assertCascadeApplied(fixture);
        assertThat(accessLogsFor(fixture.chains())).isEqualTo(1);
    }

    private Fixture createMyPersonFixture() {
        return tx.execute(status -> {
            long sourceId = saveUser("source");
            long targetId = saveUser("target");
            Relationship relationship = relationshipRepository.save(Relationship.createMyPerson(sourceId, targetId, "친구"));
            emergencyContactRepository.save(EmergencyContact.create(sourceId, targetId, 1));
            Chain chain = createChain(sourceId, targetId);
            return new Fixture(sourceId, targetId, relationship.getId(), List.of(chain));
        });
    }

    private Fixture createGuardianFixture() {
        return tx.execute(status -> {
            long sourceId = saveUser("guardian-a");
            long targetId = saveUser("guardian-b");
            Relationship relationship = relationshipRepository.save(Relationship.createGuardian(sourceId, targetId, 1));
            relationshipRepository.save(Relationship.createGuardian(targetId, sourceId, 1));
            Chain first = createChain(sourceId, targetId);
            Chain second = createChain(targetId, sourceId);
            return new Fixture(sourceId, targetId, relationship.getId(), List.of(first, second));
        });
    }

    private long saveUser(String prefix) {
        return userRepository.save(User.register(prefix + "-" + UUID.randomUUID() + "@example.com", "test-hash", prefix))
                .getId();
    }

    private Chain createChain(long ownerId, long targetId) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Situation situation = situationRepository.save(Situation.create(ownerId, "RECOVERY", "통합 검증",
                now.minusDays(1), now.plusDays(2)));
        participantRepository.save(SituationParticipant.create(situation, ownerId,
                SituationParticipantRole.OWNER, now.minusHours(1)));
        SituationParticipant participant = participantRepository.save(SituationParticipant.create(situation, targetId,
                SituationParticipantRole.PARTICIPANT, now.minusMinutes(30)));

        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(situation.getId(), ownerId,
                HelpType.CHECK_IN, null, "집", now.plusDays(1), now.plusDays(1).plusHours(1), null));
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, targetId, 1, request.getStartsAt()));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, now);
        request.accept();

        SafetyInformation information = safetyInformationRepository.save(
                SafetyInformation.create(ownerId, InformationType.MEDICATION, "복용약", "하루 한 번"));
        RequestInformationPermission permission = permissionRepository.save(RequestInformationPermission.createActive(
                request.getId(), assignment.getId(), information.getId(), InformationType.MEDICATION, now, null));
        return new Chain(situation.getId(), participant.getId(), request.getId(), assignment.getId(), permission.getId());
    }

    private void assertCascadeApplied(Fixture fixture) {
        assertThat(relationshipRepository.findById(fixture.relationshipId()).orElseThrow().getStatus())
                .isEqualTo(RelationshipStatus.ENDED);
        fixture.chains().forEach(this::assertChainApplied);
    }

    private void assertChainApplied(Chain chain) {
        tx.executeWithoutResult(status -> {
            assertThat(participantRepository.findById(chain.participantId()).orElseThrow().getStatus())
                    .isEqualTo(SituationParticipantStatus.REMOVED);
            assertThat(careRequestRepository.findById(chain.requestId()).orElseThrow().getStatus())
                    .isEqualTo(CareRequestStatus.REQUESTED);
            var assignment = assignmentRepository.findById(chain.assignmentId()).orElseThrow();
            assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.RELATIONSHIP_ENDED);
            assertThat(assignment.getClosedAt()).isNotNull();
            var permission = permissionRepository.findById(chain.permissionId()).orElseThrow();
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ASSIGNMENT_CLOSED);
        });
    }

    private void assertCascadeRolledBack(Fixture fixture) {
        tx.executeWithoutResult(status -> {
            assertThat(relationshipRepository.findById(fixture.relationshipId()).orElseThrow().getStatus())
                    .isEqualTo(RelationshipStatus.ACTIVE);
            assertThat(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(fixture.sourceUserId()))
                    .extracting(EmergencyContact::getContactUserId)
                    .contains(fixture.targetUserId());
            fixture.chains().forEach(chain -> {
                assertThat(participantRepository.findById(chain.participantId()).orElseThrow().getStatus())
                        .isEqualTo(SituationParticipantStatus.ACTIVE);
                assertThat(careRequestRepository.findById(chain.requestId()).orElseThrow().getStatus())
                        .isEqualTo(CareRequestStatus.ACCEPTED);
                assertThat(assignmentRepository.findById(chain.assignmentId()).orElseThrow().getClosedAt()).isNull();
                assertThat(permissionRepository.findById(chain.permissionId()).orElseThrow().getStatus())
                        .isEqualTo(PermissionStatus.ACTIVE);
                assertThat(changeEventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(chain.requestId()))
                        .isEmpty();
            });
            assertThat(accessLogsFor(fixture.chains())).isZero();
        });
    }

    private long accessLogsFor(List<Chain> chains) {
        List<Long> permissionIds = chains.stream().map(Chain::permissionId).toList();
        return accessLogRepository.findAll().stream()
                .filter(log -> permissionIds.contains(log.getPermissionId()))
                .filter(log -> log.getEventType() == AccessEventType.AUTO_REVOKED)
                .count();
    }

    private enum FailurePoint { NONE, B_AFTER_PARTICIPANT_REMOVED, C_AFTER_ASSIGNMENT_CLOSED }

    private record Fixture(long sourceUserId, long targetUserId, long relationshipId, List<Chain> chains) {}
    private record Chain(long situationId, long participantId, long requestId, long assignmentId, long permissionId) {}

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(String stage) { super("injected " + stage + " failure"); }
    }

    static class FailureInjection {
        private final AtomicReference<FailurePoint> point = new AtomicReference<>(FailurePoint.NONE);

        void failAt(FailurePoint failurePoint) { point.set(failurePoint); }
        void clear() { point.set(FailurePoint.NONE); }

        @EventListener
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public void afterBParticipantChange(com.gyeot.domain.situation.event.SituationParticipantRemovedEvent event) {
            if (point.get() == FailurePoint.B_AFTER_PARTICIPANT_REMOVED) {
                throw new InjectedFailure("B");
            }
        }

        @EventListener
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public void afterCCleanup(com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent event) {
            if (point.get() == FailurePoint.C_AFTER_ASSIGNMENT_CLOSED) {
                throw new InjectedFailure("C");
            }
        }
    }
}
