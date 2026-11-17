package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 같은 공유 카드를 두 트랜잭션이 동시에 다시 허용할 때 한 건만 성공하는지 확인합니다. (F-30, #46, C 요청 행 잠금 #124)
 *
 * 기대 결과
 * - 먼저 C 요청 행을 잠근 쪽만 성공하고, 나중 쪽은 잠금을 기다린 뒤 이미 공유 중이라 409 SHARING_REALLOW_NOT_ALLOWED
 * - 항목마다 새 ACTIVE 권한과 GRANTED 이력이 각각 한 건만 추가됨
 *
 * 이 테스트가 생긴 이유
 * - C 요청 행 잠금을 연결하기 전(11/16)에는 나중 쪽도 성공했습니다. (항목별 ACTIVE 2건, GRANTED 4건)
 *   나중 쪽이 SharingCardLocker 1단계의 일반 조회에서 스냅샷을 만든 뒤 항목 잠금에서 기다려,
 *   먼저 쪽이 커밋한 새 ACTIVE를 보지 못하고 REVOKED 카드로 판단했기 때문입니다.
 * - 지금은 쓰기 트랜잭션의 첫 조회가 C 요청 행 잠금이라, 나중 쪽의 스냅샷은 먼저 쪽 커밋 뒤에 만들어집니다.
 *   잠금 연결이 빠지거나 그 앞에 일반 조회가 생기면 이 테스트가 다시 실패합니다.
 *
 * 동기화
 * 1. 먼저 쪽: 트랜잭션을 열고 C 요청 행을 잠근 뒤(서비스 0단계와 같은 조회) 신호를 보내고 멈춤
 * 2. 나중 쪽: 실제 진입점(SharingReallowService)으로 다시 허용을 시작
 * 3. 나중 쪽이 DB에서 실제로 care_requests 행 잠금을 기다릴 때까지 확인 (시간만 재지 않음)
 * 4. 먼저 쪽을 풀어 주면 같은 트랜잭션에서 다시 허용을 마저 하고 커밋, 그 뒤 나중 쪽이 이어서 실행
 *
 * - C 요청과 지정은 실제 행으로 만들고, 실제 CareRequestSharingReader로 잠급니다. (가짜로 바꾸면 잠금이 걸리지 않음)
 * - 관계(A)와 상황 참여(B)는 가짜로 바꿉니다. (SharingReallowIntegrationTest와 같은 조건)
 * - 잠금 대기 확인은 다른 트랜잭션의 잠금 정보를 봐야 해서 root 계정으로 따로 접속합니다.
 *   (Testcontainers MySQL의 root 비밀번호는 테스트 계정과 같음. 확인 방식은 CareRequestSharingReaderLockTest와 같은 data_lock_waits)
 * - 각 테스트는 커밋되므로 스레드가 끝난 뒤 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SharingReallowConcurrencyTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자, 로그인한 사용자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 11, 1, 10, 0); // 처음 수락 시각
    private static final String SUCCESS = "성공";

    @Autowired private SharingReallowService sharingReallowService;         // 나중 쪽: 실제 진입점
    @Autowired private SharingReallowTransaction sharingReallowTransaction; // 먼저 쪽: 미리 잡은 잠금과 같은 트랜잭션에서 실행
    @Autowired private SharingRevokeService sharingRevokeService;           // 다시 허용할 카드(직접 철회 상태) 준비
    @Autowired private CareRequestSharingReader careRequestSharingReader;   // 먼저 쪽의 C 요청 행 잠금 (실제 구현)
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul)

    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;  // A 관계 (다시 열 수 있는 상태)
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B 상황 참여 (다시 열 수 있는 상태)

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private Long requestId;    // 수락된 일반 요청
    private Long assignmentId; // 지금 담당자의 지정 = 공유 카드 ID
    private Long medication;   // 혈압약
    private Long disease;      // 당뇨약

    @BeforeEach
    void setUp() {
        acceptedRequest(); // requestId, assignmentId를 채움
        medication = saveItem("혈압약");
        disease = saveItem("당뇨약");
        saveActive(medication);
        saveActive(disease);

        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any())).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);

        sharingRevokeService.revoke(OWNER_ID, assignmentId); // 실제 철회 API로 다시 허용할 수 있는 카드(REVOKED)를 만듦
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
        assignmentRepository.deleteAllInBatch();  // 지정이 요청을 참조하므로 먼저 지움
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 같은_카드를_동시에_다시_허용하면_한_건만_성공하고_권한과_이력이_중복되지_않는다() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);  // 먼저 쪽이 C 요청 행 잠금을 잡았다는 신호
        CountDownLatch releaseFirst = new CountDownLatch(1); // 먼저 쪽을 계속하게 하는 신호
        int baseLogCount = accessLogRepository.findAll().size(); // 준비 단계 철회 이력 (다시 허용 전)

        // 1. 먼저 쪽: C 요청 행 잠금을 쥔 채 멈춘 뒤, 같은 트랜잭션에서 다시 허용 (REQUIRED라 이 트랜잭션에 참여)
        //    서비스와 같은 순서가 되도록 잠금 조회를 이 트랜잭션의 첫 조회로 함
        Future<SharingReallowResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
            careRequestSharingReader.findByIdForUpdate(requestId);
            firstLocked.countDown();
            await(releaseFirst);
            return sharingReallowTransaction.reallow(OWNER_ID, assignmentId, requestId); // 이미 잡은 잠금은 기다리지 않음
        }));

        Future<SharingReallowResponse> second;
        try {
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).as("먼저 쪽이 C 요청 행 잠금을 잡아야 합니다").isTrue();

            // 2. 나중 쪽: 실제 진입점 (요청 ID 조회 뒤 새 쓰기 트랜잭션의 첫 조회로 C 요청 행 잠금)
            second = executor.submit(() -> sharingReallowService.reallow(OWNER_ID, assignmentId));

            // 3. 나중 쪽이 실제로 C 요청 행 잠금을 기다리는지 DB에서 확인 (시작 전이라 안 끝난 경우와 구분)
            assertThat(waitForRequestLockWait(10_000)).as("나중 쪽이 C 요청 행 잠금에서 기다려야 합니다").isTrue();
            assertThat(second.isDone()).as("나중 쪽은 먼저 쪽 커밋 전에 끝나면 안 됩니다").isFalse();

            releaseFirst.countDown(); // 4. 먼저 쪽 진행, 커밋
        } finally {
            releaseFirst.countDown(); // 확인이 실패해도 먼저 쪽 트랜잭션을 끝내 잠금을 풀어 줌 (이미 0이면 아무 일 없음)
        }

        String firstOutcome = outcome(first);
        String secondOutcome = outcome(second);

        List<RequestInformationPermission> permissions = permissionRepository.findAll();
        Map<Long, Long> activeCountByItem = permissions.stream()
                .filter(permission -> permission.getStatus() == PermissionStatus.ACTIVE)
                .collect(Collectors.groupingBy(RequestInformationPermission::getInformationId, Collectors.counting()));
        List<InformationAccessLog> grantedLogs = accessLogRepository.findAll().stream()
                .filter(log -> log.getEventType() == AccessEventType.GRANTED)
                .toList();

        // 실패해도 모든 차이가 한 번에 보이게 모아서 확인
        assertSoftly(softly -> {
            softly.assertThat(firstOutcome).as("먼저 쪽 결과").isEqualTo(SUCCESS);
            softly.assertThat(secondOutcome).as("나중 쪽 결과")
                    .isEqualTo(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED.toString());
            softly.assertThat(activeCountByItem).as("항목별 ACTIVE 권한 수")
                    .containsExactlyInAnyOrderEntriesOf(Map.of(medication, 1L, disease, 1L));
            softly.assertThat(grantedLogs).as("다시 허용으로 추가된 GRANTED 이력 수").hasSize(2);
            softly.assertThat(accessLogRepository.findAll()).as("전체 이력 수 (철회 이력 + GRANTED 2건)")
                    .hasSize(baseLogCount + 2);
        });
    }

    // === 도우미 ===

    /**
     * 다른 트랜잭션이 care_requests 행 잠금을 기다리는 상태가 될 때까지 짧은 간격으로 확인합니다.
     * 기다리는 잠금의 테이블까지 확인해, 항목이나 권한 잠금에서 기다리는 경우(C 잠금이 빠진 경우)와 구분합니다.
     * performance_schema는 권한이 필요해 root로 따로 접속합니다.
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
     * 종료 시각은 서비스 시계 기준 하루 뒤라 테스트 중에 요청 조건(종료 전)을 벗어나지 않습니다.
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
