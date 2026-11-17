package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedInformationResponse;
import com.gyeot.domain.access.dto.response.SharedInformationResponse.SharedItem;
import com.gyeot.domain.access.dto.response.UnavailableReason;
import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL에서 전달받은 정보 조회의 VIEWED 기록과 조회 경합 기준을 확인합니다. (#45)
 *
 * - 조회 기준: 접근 조건과 항목은 같은 트랜잭션의 첫 일반 DB 조회에서 정해진 스냅샷을 기준으로 판단합니다.
 *   조회 도중 다른 트랜잭션에서 커밋된 삭제나 철회는 이번 응답에 반영되지 않을 수 있고, 다음 조회부터 반영됩니다.
 * - 경합 테스트 순서: 조회가 권한을 읽은 뒤(스냅샷 생성) 멈춤 → 별도 트랜잭션에서 삭제 또는 철회 후 커밋
 *   → 진행 중 조회는 예전 스냅샷 기준으로 응답하고 VIEWED 기록 → 새 조회는 변경을 반영
 * - 조회는 행 잠금을 쓰지 않으므로, 조회가 멈춰 있는 동안 삭제와 철회가 기다리지 않고 끝나는지도 함께 확인합니다.
 * - 생성, 수락, 완료 이벤트를 실제 D 리스너로 처리해, 부여 시각과 회수 결과가 조회 판단과 맞는지도 확인합니다.
 *
 * C, A, B 조회 계약은 Mock으로 둡니다. 이 테스트의 대상은 D의 권한, 항목, 이력 조회이고,
 * 조회가 멈추는 지점은 상황 참여 확인(B Mock)입니다. 서비스는 그 앞에서 권한을 읽어 스냅샷을 이미 만들었습니다.
 * 각 테스트는 커밋되므로 끝나면 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SharedInformationIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUEST_ID = 31L;
    private static final Long REQUESTER_ID = 10L;  // 요청자 = 정보 주인
    private static final Long ASSIGNEE_ID = 20L;   // 조회하는 담당자
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 11, 14, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2099, 1, 1, 0, 0); // 실제 시계로 돌므로 먼 미래

    @Autowired private SharedInformationService sharedInformationService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private SafetyInformationService safetyInformationService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;         // C: 요청 상태와 현재 담당자
    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;   // A: 관계
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B: 상황 참여 (멈춤 지점)

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // 조회를 상황 참여 확인 지점에서 한 번만 멈추게 하는 장치
    private final AtomicBoolean pauseArmed = new AtomicBoolean(false);
    private final CountDownLatch viewerPaused = new CountDownLatch(1); // 조회가 스냅샷을 만든 뒤 멈췄다는 신호
    private final CountDownLatch resumeViewer = new CountDownLatch(1); // 다른 트랜잭션이 커밋했으니 조회를 이어가라는 신호

    private Long medicationId;
    private Long permissionId;

    @BeforeEach
    void setUp() {
        // 정보 주인의 항목 하나와, 수락 때 활성화된 권한 하나 (공유 중)
        medicationId = safetyInformationRepository.save(
                SafetyInformation.create(REQUESTER_ID, InformationType.MEDICATION, "혈압약", "아침 1정")).getId();
        permissionId = permissionRepository.save(RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, medicationId, InformationType.MEDICATION, ACCEPTED_AT, null)).getId();

        // C: 진행 중인 일반 요청, 조회하는 사람이 지금 담당자
        SharingRequestInfo request = new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID,
                CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, ACCEPTED_AT, ENDS_AT, SITUATION_ID,
                ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL);
        when(careRequestSharingReader.hasBeenAssigned(REQUEST_ID, ASSIGNEE_ID)).thenReturn(true);
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(REQUEST_ID, request)); // 항목 삭제 알림용

        // A: 요청자 → 담당자 관계 유효
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                REQUESTER_ID, ASSIGNEE_ID, RelationshipStatus.ACTIVE)).thenReturn(true);

        // B: 상황 참여 중. 멈춤을 켠 테스트에서는 처음 한 번만 여기서 멈춤 (권한 조회로 스냅샷이 만들어진 뒤)
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenAnswer(invocation -> {
            if (pauseArmed.compareAndSet(true, false)) {
                viewerPaused.countDown();
                if (!resumeViewer.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("조회를 이어가라는 신호가 오지 않았습니다.");
                }
            }
            return true;
        });
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        resumeViewer.countDown(); // 실패한 테스트에서도 멈춘 조회가 끝나도록 풀어 줌
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
    }

    @Test
    void 볼_수_있으면_내려준_항목마다_VIEWED가_DB에_저장된다() {
        SharedInformationResponse response = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);

        assertThat(response.accessible()).isTrue();
        assertThat(response.categories().get(0).items()).extracting(SharedItem::informationId).containsExactly(medicationId);
        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getOwnerUserId,
                        InformationAccessLog::getActorUserId, InformationAccessLog::getInformationId,
                        InformationAccessLog::getEventType)
                .containsExactly(tuple(permissionId, REQUESTER_ID, ASSIGNEE_ID, medicationId, AccessEventType.VIEWED));
    }

    @Test
    void 생성과_수락_이벤트로_활성화한_항목은_수락_시각에_부여되고_추가됨이_아니다() {
        createAndAcceptThroughEvents();

        RequestInformationPermission permission = permissionRepository.findById(permissionId).orElseThrow();
        assertThat(permission.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(permission.getGrantedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getEventType, InformationAccessLog::getPermissionId,
                        InformationAccessLog::getActorUserId, InformationAccessLog::getOccurredAt)
                .containsExactly(tuple(AccessEventType.GRANTED, permissionId, ASSIGNEE_ID, ACCEPTED_AT));

        SharedInformationResponse response = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(response.accessible()).isTrue();
        assertThat(response.categories().get(0).items())
                .extracting(SharedItem::informationId, SharedItem::added)
                .containsExactly(tuple(medicationId, false));
        assertThat(viewedCount()).isEqualTo(1);
    }

    @Test
    void 완료_이벤트가_권한을_회수하고_완료된_요청_조회는_내용과_VIEWED를_추가하지_않는다() {
        createAndAcceptThroughEvents();
        LocalDateTime completedAt = ACCEPTED_AT.plusHours(1);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new CareRequestCompletedEvent(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, ASSIGNEE_ID, ASSIGNEE_ID, completedAt)));

        RequestInformationPermission permission = permissionRepository.findById(permissionId).orElseThrow();
        assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
        assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.REQUEST_ENDED);
        assertThat(permission.getRevokedAt()).isEqualTo(completedAt);
        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(AccessEventType.GRANTED, AccessEventType.AUTO_REVOKED);

        // C 상태가 아직 진행 중으로 보이더라도, 실제 회수된 권한으로는 내용을 내주지 않습니다.
        SharedInformationResponse revokedResponse = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(revokedResponse.categories()).isEmpty();
        assertThat(viewedCount()).isZero();

        // C의 상태 변경은 Mock으로 제공합니다. 실제 C 완료 서비스나 API를 검증하는 테스트는 아닙니다.
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, CareRequestStatus.COMPLETED, RequestType.GENERAL,
                ACCEPTED_AT, ENDS_AT, SITUATION_ID, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL)));
        SharedInformationResponse response = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(response.accessible()).isFalse();
        assertThat(response.unavailableReason()).isEqualTo(UnavailableReason.REQUEST_COMPLETED);
        assertThat(response.categories()).isEmpty();
        assertThat(viewedCount()).isZero();
    }

    @Test
    void 조회_도중_항목_삭제가_커밋돼도_진행_중_조회는_스냅샷_기준이고_다음_조회부터_빠진다() throws Exception {
        // 진행 중 조회: 권한을 읽은 뒤 멈춤
        Future<SharedInformationResponse> inFlight = startViewerAndWaitUntilPaused();

        // 별도 트랜잭션에서 항목 삭제 (실제 삭제 경로: 항목 잠금 → 권한 회수 → 항목 ID 비우기 → 삭제) 후 커밋
        // 조회가 잠금을 쓰지 않으므로 삭제는 기다리지 않고 끝나야 함
        executor.submit(() -> safetyInformationService.delete(REQUESTER_ID, medicationId)).get(5, TimeUnit.SECONDS);
        assertThat(safetyInformationRepository.findById(medicationId)).isEmpty();

        // 진행 중 조회를 이어감 → 예전 스냅샷의 항목을 내려주고 VIEWED 기록
        resumeViewer.countDown();
        SharedInformationResponse snapshotResponse = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(snapshotResponse.accessible()).isTrue();
        assertThat(snapshotResponse.categories().get(0).items()).extracting(SharedItem::informationId)
                .containsExactly(medicationId);
        assertThat(viewedCount()).isEqualTo(1);

        // 새 조회 → 삭제 반영: 볼 수 있지만 빈 목록, 이력 추가 없음
        SharedInformationResponse nextResponse = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(nextResponse.accessible()).isTrue();
        assertThat(nextResponse.categories()).isEmpty();
        assertThat(viewedCount()).isEqualTo(1);
    }

    @Test
    void 조회_도중_직접_철회가_커밋돼도_진행_중_조회는_스냅샷_기준이고_다음_조회부터_막힌다() throws Exception {
        Future<SharedInformationResponse> inFlight = startViewerAndWaitUntilPaused();

        // 별도 트랜잭션에서 정보 주인이 직접 철회 (공유 관리 #46 전이라 권한 잠금 조회 후 회수로 같은 상태를 만듦) 후 커밋
        executor.submit(() -> transactionTemplate.executeWithoutResult(status ->
                permissionRepository.findAllByAssignmentIdAndStatusIn(ASSIGNMENT_ID, List.of(PermissionStatus.ACTIVE))
                        .forEach(permission -> permission.revoke(RevokeReason.OWNER_REVOKED, LocalDateTime.now()))))
                .get(5, TimeUnit.SECONDS);

        // 진행 중 조회 → 예전 스냅샷 기준으로 내용과 VIEWED
        resumeViewer.countDown();
        SharedInformationResponse snapshotResponse = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(snapshotResponse.accessible()).isTrue();
        assertThat(snapshotResponse.categories()).hasSize(1);
        assertThat(viewedCount()).isEqualTo(1);

        // 새 조회 → 직접 철회 반영
        SharedInformationResponse nextResponse = sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(nextResponse.accessible()).isFalse();
        assertThat(nextResponse.unavailableReason()).isEqualTo(UnavailableReason.OWNER_REVOKED);
        assertThat(nextResponse.categories()).isEmpty();
        assertThat(viewedCount()).isEqualTo(1);
    }

    // === 도우미 ===

    /**
     * 직접 저장한 기본 권한 대신, 실제 D 리스너가 생성과 수락 이벤트로 권한을 만들게 합니다.
     * C Reader의 acceptedAt은 이벤트 occurredAt과 같은 값을 제공합니다.
     * D가 이벤트 시각을 grantedAt으로 저장하는 계약을 검증하며, 실제 C 수락 서비스의 시각 일치는 검증하지 않습니다.
     */
    private void createAndAcceptThroughEvents() {
        permissionRepository.deleteAll();
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new CareRequestCreatedEvent(
                REQUEST_ID, REQUESTER_ID, RequestType.GENERAL, ASSIGNMENT_ID, ASSIGNEE_ID,
                List.of(medicationId), ACCEPTED_AT.minusHours(1))));
        assertThat(permissionRepository.findAll()).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.PENDING);
            assertThat(permission.getGrantedAt()).isNull();
        });
        assertThat(accessLogRepository.findAll()).isEmpty();

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new CareRequestAcceptedEvent(
                REQUEST_ID, REQUESTER_ID, RequestType.GENERAL, ASSIGNMENT_ID, ASSIGNEE_ID, ACCEPTED_AT)));
        assertThat(permissionRepository.findAll()).hasSize(1);
        permissionId = permissionRepository.findAll().get(0).getId();
    }

    /** 다른 스레드에서 조회를 시작하고, 권한을 읽어 스냅샷을 만든 뒤 멈출 때까지 기다림 */
    private Future<SharedInformationResponse> startViewerAndWaitUntilPaused() throws InterruptedException {
        pauseArmed.set(true);
        Future<SharedInformationResponse> inFlight = executor.submit(
                () -> sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID));
        assertThat(viewerPaused.await(10, TimeUnit.SECONDS))
                .as("조회가 상황 참여 확인 지점까지 와야 합니다 (그 앞에서 권한을 읽어 스냅샷이 만들어짐)")
                .isTrue();
        return inFlight;
    }

    private long viewedCount() {
        return accessLogRepository.findAll().stream()
                .filter(log -> log.getEventType() == AccessEventType.VIEWED)
                .count();
    }
}
