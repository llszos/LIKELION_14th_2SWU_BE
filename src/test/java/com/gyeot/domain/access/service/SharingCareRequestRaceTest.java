package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.carerequest.service.CareRequestEditService;
import com.gyeot.domain.carerequest.service.CareRequestStatusService;
import com.gyeot.domain.carerequest.service.ChangeProposalService;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 같은 요청의 C 변경(재확인 수락으로 항목 추가, 완료, 취소)과 공유 직접 철회, 다시 허용이 겹칠 때를 확인합니다. (F-30, #46, #124)
 *
 * 두 쪽 모두 같은 C 요청 행을 먼저 잠그므로 차례로 실행되고, 나중 쪽은 먼저 쪽이 커밋한 결과를 보고 판단해야 합니다.
 * - C 항목 추가 먼저 → 직접 철회: 철회가 추가된 항목의 권한까지 회수
 * - 직접 철회 먼저 → C 항목 추가: 철회한 항목은 닫힌 채, 새 항목만 ACTIVE (재확인 수락이 철회한 항목을 다시 열지 않음)
 * - C 취소 먼저 → 다시 허용 / C 완료 먼저 → 다시 허용: 409 SHARING_REALLOW_NOT_ALLOWED, ACTIVE와 GRANTED가 새로 생기지 않음
 * - 다시 허용 먼저 → C 완료 / 다시 허용 먼저 → C 취소: 다시 허용으로 열린 권한까지 회수
 * (같은 카드 동시 다시 허용은 SharingReallowConcurrencyTest)
 *
 * 동기화 (SharingReallowConcurrencyTest와 같은 방식)
 * 1. 먼저 쪽: 트랜잭션을 열고 C 요청 행을 잠근 뒤 신호를 보내고 멈춤. 풀리면 같은 트랜잭션에서 실제 서비스를 실행하고 커밋
 * 2. 나중 쪽: 실제 서비스 진입점으로 시작
 * 3. 나중 쪽이 DB에서 실제로 care_requests 행 잠금을 기다릴 때까지 확인한 뒤 먼저 쪽을 풀어 줌
 *
 * - C 요청, 지정, 수정안은 실제 C 서비스와 엔티티로 만들고, D 권한 처리도 실제 이벤트 수신(동기)으로 일어납니다.
 * - 관계(A)와 상황 참여(B)만 가짜로 바꿉니다. (다시 허용의 canReopen용)
 * - 잠금 대기 확인은 root 계정으로 따로 접속해 performance_schema를 봅니다. (Testcontainers MySQL의 root 비밀번호는 테스트 계정과 같음)
 * - 각 테스트는 커밋되므로 스레드가 끝난 뒤 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SharingCareRequestRaceTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 11, 1, 10, 0); // 처음 수락 시각
    private static final String SUCCESS = "성공";

    // D
    @Autowired private SharingRevokeService sharingRevokeService;
    @Autowired private SharingReallowService sharingReallowService;
    @Autowired private SharingReallowTransaction sharingReallowTransaction;
    @Autowired private SharingRevokeTransaction sharingRevokeTransaction;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    // C
    @Autowired private CareRequestSharingReader careRequestSharingReader;
    @Autowired private CareRequestEditService careRequestEditService;
    @Autowired private ChangeProposalService changeProposalService;
    @Autowired private CareRequestStatusService careRequestStatusService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository changeEventRepository;

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul)

    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;  // A 관계 (다시 열 수 있는 상태)
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B 상황 참여 (다시 열 수 있는 상태)

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private Long requestId;    // 수락된 일반 요청
    private Long assignmentId; // 지금 담당자의 지정 = 공유 카드 ID
    private Long medication;   // 혈압약 (처음부터 공개)
    private Long disease;      // 당뇨약 (처음부터 공개)

    @BeforeEach
    void setUp() {
        acceptedRequest(); // requestId, assignmentId를 채움
        medication = saveItem("혈압약");
        disease = saveItem("당뇨약");
        saveActive(medication);
        saveActive(disease);

        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any())).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        // 스레드가 끝나 잠금이 모두 풀린 뒤에 지움 (남은 트랜잭션이 행을 잡고 있으면 삭제가 막힘)
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("DB 정리 전에 작업 스레드가 종료돼야 합니다")
                    .isTrue();
        }
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
        changeEventRepository.deleteAllInBatch();  // 이력과 수정안이 지정, 요청을 참조하므로 먼저 지움
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    // === C 항목 추가 먼저 → 직접 철회 ===

    @Test
    void C_재확인_수락으로_항목이_추가된_뒤_철회하면_추가된_항목까지_회수된다() throws Exception {
        // 수락 후 요청자가 공개 항목에 소변통을 더한 수정안을 보냄 (재확인 대기)
        Long added = saveItem("소변통");
        Long proposalId = careRequestEditService.update(requestId, OWNER_ID,
                new UpdateCareRequestRequest(null, null, null, List.of(medication, disease, added))).proposalId();

        // 먼저 쪽: 담당자의 재확인 수락 (D가 같은 트랜잭션에서 추가 항목 ACTIVE 생성) / 나중 쪽: 직접 철회
        race(() -> changeProposalService.accept(requestId, proposalId, ASSIGNEE_ID),
                () -> sharingRevokeService.revoke(OWNER_ID, assignmentId));

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(재확인 수락) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(직접 철회) 결과").isEqualTo(SUCCESS);
            softly.assertThat(activePermissions()).as("남은 ACTIVE 권한").isEmpty();
            softly.assertThat(ownerRevokedItemIds()).as("직접 철회된 항목 (추가된 항목 포함)")
                    .containsExactlyInAnyOrder(medication, disease, added);
        });
    }

    // === 직접 철회 먼저 → C 항목 추가 ===

    @Test
    void 철회한_뒤_C_재확인_수락으로_항목이_추가되면_철회한_항목은_닫힌_채_새_항목만_열린다() throws Exception {
        // 수락 후 요청자가 소변통을 더한 수정안을 보냄 (재확인 대기). 수정안 목록에는 기존 두 항목도 들어 있음
        Long added = saveItem("소변통");
        Long proposalId = careRequestEditService.update(requestId, OWNER_ID,
                new UpdateCareRequestRequest(null, null, null, List.of(medication, disease, added))).proposalId();

        // 먼저 쪽: 직접 철회 / 나중 쪽: 담당자의 재확인 수락
        race(() -> sharingRevokeTransaction.revoke(OWNER_ID, assignmentId, requestId),
                () -> changeProposalService.accept(requestId, proposalId, ASSIGNEE_ID));

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(직접 철회) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(재확인 수락) 결과").isEqualTo(SUCCESS);
            softly.assertThat(activePermissions()).as("ACTIVE 권한 = 새 항목만")
                    .extracting(RequestInformationPermission::getInformationId)
                    .containsExactly(added);
            softly.assertThat(ownerRevokedItemIds()).as("직접 철회된 채 남은 항목")
                    .containsExactlyInAnyOrder(medication, disease);
        });
    }

    // === C 취소 먼저 → 다시 허용 ===

    @Test
    void C가_요청을_취소한_뒤_다시_허용하면_409이고_권한과_이력이_새로_생기지_않는다() throws Exception {
        sharingRevokeService.revoke(OWNER_ID, assignmentId); // 다시 허용할 수 있는 카드(REVOKED)
        long grantedBefore = grantedLogCount();

        // 먼저 쪽: 요청자의 요청 취소 / 나중 쪽: 다시 허용
        race(() -> careRequestStatusService.cancel(requestId, OWNER_ID),
                () -> sharingReallowService.reallow(OWNER_ID, assignmentId));

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(요청 취소) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(다시 허용) 결과")
                    .isEqualTo(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED.toString());
            softly.assertThat(activePermissions()).as("끝난 요청의 ACTIVE 권한").isEmpty();
            softly.assertThat(grantedLogCount()).as("GRANTED 이력 수 (다시 허용 전과 같음)").isEqualTo(grantedBefore);
        });
    }

    // === C 완료 먼저 → 다시 허용 ===

    @Test
    void C가_요청을_완료한_뒤_다시_허용하면_409이고_권한과_이력이_새로_생기지_않는다() throws Exception {
        startRequest(); // 완료는 진행 중 요청만 가능
        sharingRevokeService.revoke(OWNER_ID, assignmentId);
        long grantedBefore = grantedLogCount();

        // 먼저 쪽: 요청자의 돌봄 완료 / 나중 쪽: 다시 허용
        race(() -> careRequestStatusService.complete(requestId, OWNER_ID),
                () -> sharingReallowService.reallow(OWNER_ID, assignmentId));

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(돌봄 완료) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(다시 허용) 결과")
                    .isEqualTo(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED.toString());
            softly.assertThat(activePermissions()).as("끝난 요청의 ACTIVE 권한").isEmpty();
            softly.assertThat(grantedLogCount()).as("GRANTED 이력 수 (다시 허용 전과 같음)").isEqualTo(grantedBefore);
        });
    }

    // === 다시 허용 먼저 → C 완료 ===

    @Test
    void 다시_허용한_뒤_C가_요청을_완료하면_다시_열린_권한까지_회수된다() throws Exception {
        startRequest(); // 완료는 진행 중 요청만 가능
        sharingRevokeService.revoke(OWNER_ID, assignmentId);

        // 먼저 쪽: 다시 허용 (새 ACTIVE 2건) / 나중 쪽: 요청자의 돌봄 완료
        race(() -> sharingReallowTransaction.reallow(OWNER_ID, assignmentId, requestId),
                () -> careRequestStatusService.complete(requestId, OWNER_ID));

        List<RequestInformationPermission> endedByCompletion = permissionRepository.findAll().stream()
                .filter(permission -> permission.getRevokeReason() == RevokeReason.REQUEST_ENDED)
                .toList();

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(다시 허용) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(돌봄 완료) 결과").isEqualTo(SUCCESS);
            softly.assertThat(activePermissions()).as("완료 뒤 남은 ACTIVE 권한").isEmpty();
            softly.assertThat(endedByCompletion).as("완료로 회수된 권한 = 다시 허용으로 열린 권한")
                    .extracting(RequestInformationPermission::getInformationId)
                    .containsExactlyInAnyOrder(medication, disease);
        });
    }

    // === 다시 허용 먼저 → C 취소 ===

    @Test
    void 다시_허용한_뒤_C가_요청을_취소하면_다시_열린_권한까지_회수된다() throws Exception {
        sharingRevokeService.revoke(OWNER_ID, assignmentId);

        // 먼저 쪽: 다시 허용 (새 ACTIVE 2건) / 나중 쪽: 요청자의 요청 취소
        race(() -> sharingReallowTransaction.reallow(OWNER_ID, assignmentId, requestId),
                () -> careRequestStatusService.cancel(requestId, OWNER_ID));

        List<RequestInformationPermission> endedByCancel = permissionRepository.findAll().stream()
                .filter(permission -> permission.getRevokeReason() == RevokeReason.REQUEST_ENDED)
                .toList();

        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽(다시 허용) 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽(요청 취소) 결과").isEqualTo(SUCCESS);
            softly.assertThat(activePermissions()).as("취소 뒤 남은 ACTIVE 권한").isEmpty();
            softly.assertThat(endedByCancel).as("취소로 회수된 권한 = 다시 허용으로 열린 권한")
                    .extracting(RequestInformationPermission::getInformationId)
                    .containsExactlyInAnyOrder(medication, disease);
        });
    }

    // === 경합 실행 ===

    private String firstOutcome;  // 먼저 쪽 결과 ("성공" 또는 업무 오류 코드 이름)
    private String secondOutcome; // 나중 쪽 결과

    /**
     * 먼저 쪽이 C 요청 행 잠금을 쥔 상태에서 나중 쪽을 시작하고, 나중 쪽이 그 잠금을 기다리는 것을 확인한 뒤 먼저 쪽을 풀어 줍니다.
     * 결과는 firstOutcome, secondOutcome에 담습니다.
     *
     * @param first  먼저 쪽 작업. C 요청 행 잠금을 잡은 트랜잭션 안에서 실행됨 (서비스 트랜잭션은 REQUIRED라 여기에 참여)
     * @param second 나중 쪽 작업. 실제 진입점이라 자기 트랜잭션의 첫 조회로 같은 잠금을 기다림
     */
    private void race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);  // 먼저 쪽이 C 요청 행 잠금을 잡았다는 신호
        CountDownLatch releaseFirst = new CountDownLatch(1); // 먼저 쪽을 계속하게 하는 신호

        Future<?> firstResult = executor.submit(() -> transactionTemplate.execute(status -> {
            careRequestSharingReader.findByIdForUpdate(requestId); // 이 트랜잭션의 첫 조회 (서비스와 같은 순서)
            firstLocked.countDown();
            await(releaseFirst);
            try {
                return first.call(); // 이미 잡은 잠금은 기다리지 않음
            } catch (RuntimeException exception) {
                throw exception; // 업무 오류는 그대로 (롤백 후 결과로 확인)
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }));

        Future<?> secondResult;
        try {
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).as("먼저 쪽이 C 요청 행 잠금을 잡아야 합니다").isTrue();

            secondResult = executor.submit(second);

            assertThat(waitForRequestLockWait(10_000)).as("나중 쪽이 C 요청 행 잠금에서 기다려야 합니다").isTrue();
            assertThat(secondResult.isDone()).as("나중 쪽은 먼저 쪽 커밋 전에 끝나면 안 됩니다").isFalse();

            releaseFirst.countDown();
        } finally {
            releaseFirst.countDown(); // 확인이 실패해도 먼저 쪽 트랜잭션을 끝내 잠금을 풀어 줌 (이미 0이면 아무 일 없음)
        }

        firstOutcome = outcome(firstResult);
        secondOutcome = outcome(secondResult);
    }

    // === 확인용 조회 ===

    private List<RequestInformationPermission> activePermissions() {
        return permissionRepository.findAll().stream()
                .filter(permission -> permission.getStatus() == PermissionStatus.ACTIVE)
                .toList();
    }

    /** 직접 철회(OWNER_REVOKED)로 회수된 권한의 항목 ID */
    private List<Long> ownerRevokedItemIds() {
        return permissionRepository.findAll().stream()
                .filter(permission -> permission.getRevokeReason() == RevokeReason.OWNER_REVOKED)
                .map(RequestInformationPermission::getInformationId)
                .toList();
    }

    private long grantedLogCount() {
        return accessLogRepository.findAll().stream()
                .filter(log -> log.getEventType() == AccessEventType.GRANTED)
                .count();
    }

    // === 도우미 ===

    /**
     * 다른 트랜잭션이 care_requests 행 잠금을 기다리는 상태가 될 때까지 짧은 간격으로 확인합니다.
     * 기다리는 잠금의 테이블까지 확인해, 항목이나 권한 잠금에서 기다리는 경우와 구분합니다.
     *
     * @return 제한 시간 안에 care_requests 잠금 대기가 보이면 true
     */
    private static boolean waitForRequestLockWait(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits w"
                                + " JOIN performance_schema.data_locks l"
                                + " ON w.REQUESTING_ENGINE_LOCK_ID = l.ENGINE_LOCK_ID"
                                + " WHERE l.OBJECT_NAME = 'care_requests'")) {
                    resultSet.next();
                    if (resultSet.getLong(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
        }
        return false;
    }

    /** 작업 결과를 "성공" 또는 업무 오류 코드 이름으로 바꿉니다. (업무 오류가 아닌 예외는 그대로 던져 원인이 가려지지 않게 함) */
    private static String outcome(Future<?> result) throws Exception {
        try {
            result.get(30, TimeUnit.SECONDS); // 나중 쪽은 먼저 쪽 커밋까지 기다리므로 여유 있게 (CI가 느린 경우)
            return SUCCESS;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof BusinessException businessException) {
                return String.valueOf(businessException.getErrorCode());
            }
            throw exception;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            // 잠금 대기 확인(최대 10초)보다 길게 기다림. 같으면 CI가 느릴 때 먼저 쪽이 먼저 시간 초과될 수 있음
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("먼저 쪽을 풀어 주는 신호가 오지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 수락된 일반 요청과 지금 담당자의 지정을 만들어 커밋합니다. (CareRequestSharingReaderLockTest와 같은 방식)
     * 시작은 서비스 시계 기준 하루 뒤라, 수정(시작 전만 가능)과 재확인 수락이 막히지 않습니다.
     */
    private void acceptedRequest() {
        LocalDateTime now = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, OWNER_ID,
                    HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", now.plusDays(1),
                    now.plusDays(1).plusHours(3), null));
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, ASSIGNEE_ID, 1, now.plusHours(12)));
            assignment.respond(AssignmentResponseStatus.AVAILABLE, now); // 수락 (accepted_at 기록)
            request.accept();
            requestId = request.getId();
            assignmentId = assignment.getId();
        });
    }

    /** 요청을 진행 중으로 바꿈 (담당자가 시작 버튼을 누른 것과 같은 상태, 엔티티로 직접) */
    private void startRequest() {
        transactionTemplate.executeWithoutResult(status ->
                careRequestRepository.findById(requestId).orElseThrow().start(LocalDateTime.now(clock)));
    }

    /** 정보 주인의 항목 저장 */
    private Long saveItem(String title) {
        return safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.MEDICATION, title, "아침 1정")).getId();
    }

    /** 이 카드의 공유 중(ACTIVE) 권한 저장 (수락으로 열린 상태) */
    private void saveActive(Long informationId) {
        permissionRepository.save(RequestInformationPermission.createActive(
                requestId, assignmentId, informationId, InformationType.MEDICATION, GRANTED_AT, null));
    }
}
