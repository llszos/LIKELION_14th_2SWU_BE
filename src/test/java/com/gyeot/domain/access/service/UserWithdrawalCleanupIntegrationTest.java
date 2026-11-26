package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.domain.user.service.UserWithdrawalService;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 회원 탈퇴(A)부터 B 참여 종료, C 요청 정리, D 안심카드와 접근 권한 정리까지 실제 MySQL 한 트랜잭션으로 확인합니다. (#49, ERD 9.6)
 *
 * - 진입점은 UserWithdrawalService.withdraw이고, B, C, D 리스너는 모두 실제 빈입니다.
 * - 리스너 실행 순서는 바꾸지 않고 스프링이 정한 한 가지 순서로만 실행합니다. (C 먼저, D 먼저를 각각 검증하지는 않음)
 * - 쓰기와 확인을 각각 커밋된 트랜잭션으로 나누려고 테스트 메서드에는 트랜잭션을 걸지 않습니다. (끝나면 직접 지움)
 * - DB 실패는 MySQL 트리거로 안심카드 원본 삭제(D 마지막 단계)에서 일으킵니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserWithdrawalCleanupIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul")
            .withCommand("--log-bin-trust-function-creators=1"); // 테스트에서 트리거를 만들 수 있게 함

    private static final String FAIL_TRIGGER = "fail_withdrawal_safety_information_delete";

    @Autowired private UserWithdrawalService withdrawalService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private UserRepository userRepository;
    @Autowired private SituationRepository situationRepository;
    @Autowired private SituationParticipantRepository participantRepository;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeEventRepository changeEventRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private SafetyInformationService safetyInformationService;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul)

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAIL_TRIGGER);
        // 이 클래스 전용 컨테이너 DB입니다. FK가 있는 자식 테이블부터 비웁니다.
        jdbcTemplate.update("DELETE FROM information_access_logs");
        jdbcTemplate.update("DELETE FROM request_information_permissions");
        jdbcTemplate.update("DELETE FROM request_change_events");
        jdbcTemplate.update("DELETE FROM request_change_proposals");
        jdbcTemplate.update("DELETE FROM request_assignments");
        jdbcTemplate.update("DELETE FROM care_requests");
        jdbcTemplate.update("DELETE FROM situation_participants");
        jdbcTemplate.update("DELETE FROM situations");
        jdbcTemplate.update("DELETE FROM safety_information");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    // === 정보 주인(요청자) 탈퇴 ===

    @Test
    void 요청자가_탈퇴하면_요청은_탈퇴로_취소되고_안심카드와_권한은_삭제되며_접근_이력은_남는다() {
        Fixture fixture = createFixture(Stage.IN_PROGRESS);

        withdrawalService.withdraw(fixture.ownerId());

        assertOwnerCleanedUp(fixture);
    }

    @Test
    void 탈퇴_전에_삭제한_항목의_과거_권한도_요청자_탈퇴_때_삭제되고_이력은_남는다() {
        Fixture fixture = createFixture(Stage.IN_PROGRESS);
        Long deletedItemId = tx.execute(status -> safetyInformationRepository.save(
                SafetyInformation.create(fixture.ownerId(), InformationType.CAUTION, "계단", "천천히 부축")).getId());
        Long deletedItemPermissionId = tx.execute(status -> permissionRepository.save(
                RequestInformationPermission.createActive(fixture.requestId(), fixture.assignmentId(), deletedItemId,
                        InformationType.CAUTION, LocalDateTime.now(clock).minusMinutes(5), null)).getId());

        // 정보 주인이 항목을 먼저 삭제 (F-30, ERD 9.5): 권한은 회수되고 information_id가 NULL이 됨
        safetyInformationService.delete(fixture.ownerId(), deletedItemId);
        RequestInformationPermission beforeWithdrawal = permissionRepository.findById(deletedItemPermissionId).orElseThrow();
        assertThat(beforeWithdrawal.getInformationId()).isNull();
        assertThat(beforeWithdrawal.getRevokeReason()).isEqualTo(RevokeReason.ITEM_DELETED);

        withdrawalService.withdraw(fixture.ownerId());

        assertThat(permissionRepository.findById(deletedItemPermissionId)).isEmpty();
        assertThat(permissionRepository.findById(fixture.permissionId())).isEmpty();
        // 항목 삭제 때 남은 회수 이력도 보존되고, 모든 이력의 permission_id가 끊김
        assertThat(ownerLogs(fixture))
                .allSatisfy(log -> assertThat(log.getPermissionId()).isNull())
                .filteredOn(log -> deletedItemId.equals(log.getInformationId()))
                .extracting(InformationAccessLog::getEventType)
                .containsExactly(AccessEventType.AUTO_REVOKED);
    }

    @Test
    void 탈퇴_이벤트가_다시_와도_요청_이력과_접근_이력이_늘지_않는다() {
        Fixture fixture = createFixture(Stage.IN_PROGRESS);
        withdrawalService.withdraw(fixture.ownerId());
        int requestEventCount = requestEventCount(fixture);
        int accessLogCount = ownerLogs(fixture).size();
        LocalDateTime withdrawnAt = userRepository.findById(fixture.ownerId()).orElseThrow().getWithdrawnAt();

        // 같은 이벤트가 한 번 더 전달된 경우 (B, C, D 리스너가 모두 다시 실행됨)
        tx.executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(fixture.ownerId(), withdrawnAt)));

        assertOwnerCleanedUp(fixture);
        assertThat(requestEventCount(fixture)).isEqualTo(requestEventCount);
        assertThat(ownerLogs(fixture)).hasSize(accessLogCount);

        // 탈퇴 API를 다시 부르면 A에서 막힘
        assertThatThrownBy(() -> withdrawalService.withdraw(fixture.ownerId()))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.USER_ALREADY_WITHDRAWN));
    }

    @Test
    void 안심카드_삭제에서_DB_오류가_나면_탈퇴_전체가_롤백되고_다시_시도하면_정리된다() {
        Fixture fixture = createFixture(Stage.IN_PROGRESS);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_withdrawal_safety_information_delete BEFORE DELETE ON safety_information
                FOR EACH ROW
                BEGIN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected D failure';
                END
                """);
        int requestEventCount = requestEventCount(fixture);

        assertThatThrownBy(() -> withdrawalService.withdraw(fixture.ownerId()))
                .isInstanceOf(RuntimeException.class)
                .satisfies(exception -> assertThat(causeChainContains(exception, "injected D failure"))
                        .as("D 안심카드 삭제 단계에서 주입한 실패가 예외 원인 체인에 있어야 합니다.")
                        .isTrue());

        assertNothingChanged(fixture);
        assertThat(requestEventCount(fixture)).isEqualTo(requestEventCount); // C 요청 이력도 되돌아감

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAIL_TRIGGER);
        withdrawalService.withdraw(fixture.ownerId());
        assertOwnerCleanedUp(fixture);
    }

    // === 담당자, 대상자 탈퇴 ===

    @Test
    void 진행_중_담당자가_탈퇴하면_요청은_취소되고_권한은_탈퇴_사유로_회수되며_행이_남는다() {
        Fixture fixture = createFixture(Stage.IN_PROGRESS);

        withdrawalService.withdraw(fixture.assigneeId());

        tx.executeWithoutResult(status -> {
            CareRequest request = careRequestRepository.findById(fixture.requestId()).orElseThrow();
            assertThat(request.getStatus()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(request.getCancelReason()).isEqualTo(CancelReason.USER_WITHDRAWN);
            assertThat(participantRepository.findById(fixture.assigneeParticipantId()).orElseThrow().getStatus())
                    .isEqualTo(SituationParticipantStatus.LEFT);
        });
        assertPermissionRevokedForWithdrawal(fixture);
        assertOwnerItemsKept(fixture);
        assertThat(ownerLogs(fixture))
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getEventType,
                        InformationAccessLog::getActorUserId)
                .containsExactlyInAnyOrder(
                        tuple(fixture.permissionId(), AccessEventType.GRANTED, fixture.assigneeId()),
                        tuple(fixture.permissionId(), AccessEventType.VIEWED, fixture.assigneeId()),
                        tuple(fixture.permissionId(), AccessEventType.AUTO_REVOKED, null)); // 자동 회수라 수행자 없음
    }

    @Test
    void 시작_전_수락한_담당자가_탈퇴하면_요청은_요청됨으로_돌아가고_권한은_탈퇴_사유로_회수된다() {
        Fixture fixture = createFixture(Stage.ACCEPTED);

        withdrawalService.withdraw(fixture.assigneeId());

        // 지정만 USER_WITHDRAWN으로 마감하고 요청은 REQUESTED로 복귀 (ERD 9.6, 관계 해제와 같은 처리)
        tx.executeWithoutResult(status -> {
            assertThat(careRequestRepository.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(CareRequestStatus.REQUESTED);
            RequestAssignment assignment = assignmentRepository.findById(fixture.assignmentId()).orElseThrow();
            assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.USER_WITHDRAWN);
            assertThat(assignment.getClosedAt()).isNotNull();
        });
        assertPermissionRevokedForWithdrawal(fixture);
        assertOwnerItemsKept(fixture);
        assertThat(ownerLogs(fixture))
                .extracting(InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(AccessEventType.GRANTED, AccessEventType.AUTO_REVOKED);
    }

    @Test
    void 수락_전_대상자가_탈퇴하면_지정만_마감되고_요청은_유지되며_PENDING_권한이_탈퇴_사유로_회수된다() {
        Fixture fixture = createFixture(Stage.REQUESTED);

        withdrawalService.withdraw(fixture.assigneeId());

        tx.executeWithoutResult(status -> {
            assertThat(careRequestRepository.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(CareRequestStatus.REQUESTED);
            RequestAssignment assignment = assignmentRepository.findById(fixture.assignmentId()).orElseThrow();
            assertThat(assignment.getCloseReason()).isEqualTo(AssignmentCloseReason.USER_WITHDRAWN);
            assertThat(assignment.getClosedAt()).isNotNull();
        });
        assertPermissionRevokedForWithdrawal(fixture);
        assertOwnerItemsKept(fixture);
        assertThat(ownerLogs(fixture)).isEmpty(); // 열람 전이던 PENDING 권한은 회수 이력을 남기지 않음
    }

    // === 확인 도우미 ===

    /** 정보 주인 탈퇴 후 상태: 요청 취소, 원본과 권한 삭제, 이력은 permission_id만 끊기고 남음 */
    private void assertOwnerCleanedUp(Fixture fixture) {
        tx.executeWithoutResult(status -> {
            User owner = userRepository.findById(fixture.ownerId()).orElseThrow();
            assertThat(owner.getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
            assertThat(owner.getEmail()).isNull();
            // B: 다른 사람 상황의 본인 참여는 LEFT
            assertThat(participantRepository.findById(fixture.ownerOtherParticipantId()).orElseThrow().getStatus())
                    .isEqualTo(SituationParticipantStatus.LEFT);

            CareRequest request = careRequestRepository.findById(fixture.requestId()).orElseThrow();
            assertThat(request.getStatus()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(request.getCancelReason()).isEqualTo(CancelReason.USER_WITHDRAWN);
            assertThat(assignmentRepository.findById(fixture.assignmentId()).orElseThrow().getCloseReason())
                    .isEqualTo(AssignmentCloseReason.REQUEST_ENDED);

            assertThat(safetyInformationRepository.findById(fixture.sharedItemId())).isEmpty();
            assertThat(safetyInformationRepository.findById(fixture.privateItemId())).isEmpty();
            assertThat(permissionRepository.findById(fixture.permissionId())).isEmpty();
        });
        // 기존 이력 2건 + 회수 이력 1건 (C 취소와 D 탈퇴 정리가 모두 실행돼도 회수 이력은 1건)
        assertThat(ownerLogs(fixture))
                .filteredOn(log -> fixture.sharedItemId().equals(log.getInformationId()))
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getEventType,
                        InformationAccessLog::getInformationId)
                .containsExactlyInAnyOrder(
                        tuple(null, AccessEventType.GRANTED, fixture.sharedItemId()),
                        tuple(null, AccessEventType.VIEWED, fixture.sharedItemId()),
                        tuple(null, AccessEventType.AUTO_REVOKED, fixture.sharedItemId()));
    }

    /** 롤백 후 상태: 탈퇴 전과 같음 */
    private void assertNothingChanged(Fixture fixture) {
        tx.executeWithoutResult(status -> {
            User owner = userRepository.findById(fixture.ownerId()).orElseThrow();
            assertThat(owner.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(owner.getEmail()).isNotNull();
            assertThat(participantRepository.findById(fixture.ownerOtherParticipantId()).orElseThrow().getStatus())
                    .isEqualTo(SituationParticipantStatus.ACTIVE); // B 참여 종료도 되돌아감

            assertThat(careRequestRepository.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(CareRequestStatus.IN_PROGRESS);
            assertThat(assignmentRepository.findById(fixture.assignmentId()).orElseThrow().getClosedAt()).isNull();
            assertThat(permissionRepository.findById(fixture.permissionId()).orElseThrow().getStatus())
                    .isEqualTo(PermissionStatus.ACTIVE);
        });
        assertOwnerItemsKept(fixture);
        assertThat(ownerLogs(fixture))
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(
                        tuple(fixture.permissionId(), AccessEventType.GRANTED),
                        tuple(fixture.permissionId(), AccessEventType.VIEWED));
    }

    /** 담당자, 대상자 탈퇴 후 권한: 행은 남고 REVOKED + USER_WITHDRAWN */
    private void assertPermissionRevokedForWithdrawal(Fixture fixture) {
        RequestInformationPermission permission = permissionRepository.findById(fixture.permissionId()).orElseThrow();
        assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
        assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.USER_WITHDRAWN);
        assertThat(permission.getRevokedAt()).isNotNull();
    }

    /** 정보 주인이 탈퇴하지 않았으면 안심카드 원본은 그대로 */
    private void assertOwnerItemsKept(Fixture fixture) {
        assertThat(safetyInformationRepository.findById(fixture.sharedItemId())).isPresent();
        assertThat(safetyInformationRepository.findById(fixture.privateItemId())).isPresent();
    }

    /** 정보 주인 기준 접근 이력 (permission_id가 NULL이 돼도 owner_user_id로 찾음) */
    private List<InformationAccessLog> ownerLogs(Fixture fixture) {
        return accessLogRepository.findAll().stream()
                .filter(log -> fixture.ownerId().equals(log.getOwnerUserId()))
                .toList();
    }

    private int requestEventCount(Fixture fixture) {
        return changeEventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(fixture.requestId()).size();
    }

    private boolean causeChainContains(Throwable throwable, String expectedMessage) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expectedMessage)) {
                return true;
            }
        }
        return false;
    }

    // === 데이터 준비 ===

    private enum Stage { REQUESTED, ACCEPTED, IN_PROGRESS }

    /**
     * 정보 주인의 상황 1개, 요청 1개, 지정 1개와 안심카드 2개(공개 1, 비공개 1)를 만듭니다.
     * 정보 주인은 담당자가 만든 다른 상황에도 참여자로 들어가 있습니다. (탈퇴 시 B가 LEFT로 바꾸는 대상)
     * - REQUESTED: 대상자가 아직 응답 전, 공개 항목 권한 PENDING
     * - ACCEPTED: 시작 전 수락, 권한 ACTIVE, GRANTED 이력
     * - IN_PROGRESS: 진행 중, 권한 ACTIVE, GRANTED와 VIEWED 이력
     */
    private Fixture createFixture(Stage stage) {
        return tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            boolean inProgress = stage == Stage.IN_PROGRESS;
            LocalDateTime startsAt = inProgress ? now.minusMinutes(10) : now.plusDays(1);
            LocalDateTime endsAt = inProgress ? now.plusMinutes(50) : startsAt.plusHours(1);

            Long ownerId = saveUser("owner");
            Long assigneeId = saveUser("assignee");

            Situation situation = situationRepository.save(Situation.create(ownerId, "RECOVERY", "탈퇴 통합 검증",
                    now.minusDays(1), now.plusDays(2)));
            participantRepository.save(SituationParticipant.create(situation, ownerId,
                    SituationParticipantRole.OWNER, now.minusHours(2)));
            SituationParticipant assigneeParticipant = participantRepository.save(SituationParticipant.create(
                    situation, assigneeId, SituationParticipantRole.PARTICIPANT, now.minusHours(1)));

            Situation otherSituation = situationRepository.save(Situation.create(assigneeId, "HOSPITAL_VISIT",
                    "담당자의 상황", now.minusDays(1), now.plusDays(2)));
            participantRepository.save(SituationParticipant.create(otherSituation, assigneeId,
                    SituationParticipantRole.OWNER, now.minusHours(2)));
            SituationParticipant ownerOtherParticipant = participantRepository.save(SituationParticipant.create(
                    otherSituation, ownerId, SituationParticipantRole.PARTICIPANT, now.minusHours(1)));

            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(situation.getId(), ownerId,
                    HelpType.CHECK_IN, null, "집", startsAt, endsAt, null));
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, assigneeId, 1, startsAt));

            Long sharedItemId = safetyInformationRepository.save(
                    SafetyInformation.create(ownerId, InformationType.MEDICATION, "혈압약", "아침 식후 1알")).getId();
            Long privateItemId = safetyInformationRepository.save(
                    SafetyInformation.create(ownerId, InformationType.ALLERGY, "땅콩", "먹으면 두드러기")).getId();

            RequestInformationPermission permission;
            if (stage == Stage.REQUESTED) {
                permission = permissionRepository.save(RequestInformationPermission.createPending(
                        request.getId(), assignment.getId(), sharedItemId, InformationType.MEDICATION));
            } else {
                LocalDateTime acceptedAt = inProgress ? startsAt.minusMinutes(5) : now.minusMinutes(5);
                assignment.respond(AssignmentResponseStatus.AVAILABLE, acceptedAt);
                request.accept();
                if (inProgress) {
                    request.start(now);
                }
                permission = permissionRepository.save(RequestInformationPermission.createActive(
                        request.getId(), assignment.getId(), sharedItemId, InformationType.MEDICATION,
                        acceptedAt, null));
                saveLog(permission, ownerId, assigneeId, sharedItemId, AccessEventType.GRANTED, acceptedAt);
                if (inProgress) {
                    saveLog(permission, ownerId, assigneeId, sharedItemId, AccessEventType.VIEWED, now.minusMinutes(1));
                }
            }

            return new Fixture(ownerId, assigneeId, assigneeParticipant.getId(), ownerOtherParticipant.getId(),
                    request.getId(), assignment.getId(), sharedItemId, privateItemId, permission.getId());
        });
    }

    private Long saveUser(String prefix) {
        return userRepository.save(User.register(prefix + "-" + UUID.randomUUID() + "@example.com", "test-hash", prefix))
                .getId();
    }

    private void saveLog(RequestInformationPermission permission, Long ownerId, Long actorId, Long informationId,
                         AccessEventType eventType, LocalDateTime occurredAt) {
        accessLogRepository.save(InformationAccessLog.create(permission.getId(), ownerId, actorId,
                informationId, InformationType.MEDICATION, eventType, occurredAt));
    }

    private record Fixture(Long ownerId, Long assigneeId, Long assigneeParticipantId, Long ownerOtherParticipantId,
                           Long requestId, Long assignmentId, Long sharedItemId, Long privateItemId,
                           Long permissionId) {}
}
