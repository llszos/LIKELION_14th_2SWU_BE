package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.event.PermissionRevokedEvent;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 공유 다시 허용이 실제 DB에서 한 트랜잭션으로 처리되는지 확인합니다. (F-30, #46)
 *
 * - 진입점(SharingReallowService)부터 쓰기 트랜잭션, PermissionWriter, DB까지 실제로 이어서 실행합니다.
 *   요청 ID 조회와 쓰기가 다른 트랜잭션으로 나뉘어 있어, 테스트 메서드에는 트랜잭션을 걸지 않습니다. (각 테스트는 커밋되므로 끝나면 직접 지움)
 * - 직접 철회 상태는 실제 철회 API(SharingRevokeService)로 만듭니다. 철회와 다시 허용이 이어서 동작하는지까지 확인하기 위함입니다.
 * - C 요청 조회, 관계(A), 상황 참여(B)는 가짜로 바꿉니다. (실제 확인 규칙은 SharingCardReaderTest)
 * - C 요청 행 잠금과 동시 다시 허용, 동시 삭제 경합은 C 잠금 계약 반영 뒤 별도 동시성 테스트에서 확인합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
class SharingReallowIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자, 로그인한 사용자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long REQUEST_ID = 31L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 11, 1, 10, 0); // 처음 수락 시각

    @Autowired private SharingReallowService sharingReallowService;
    @Autowired private SharingRevokeService sharingRevokeService;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private TransactionTemplate transactionTemplate; // 테스트에서 항목 삭제(수정 쿼리)를 트랜잭션으로 실행
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul). JVM 기본 시간대로 지금을 구하면 CI(UTC)에서 9시간 어긋남

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;         // C 요청 (진행 중인 일반 요청)
    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;  // A 관계 (다시 열 수 있는 상태)
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B 상황 참여 (다시 열 수 있는 상태)

    private Long medication; // 혈압약 (처음부터 공유)
    private Long disease;    // 당뇨약 (처음부터 공유)

    @BeforeEach
    void setUp() {
        medication = saveItem("혈압약");
        disease = saveItem("당뇨약");
        saveActive(medication);
        saveActive(disease);

        givenRequest(CareRequestStatus.IN_PROGRESS);
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any())).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 철회한_카드를_다시_허용하면_새_ACTIVE_권한이_생기고_철회_기록은_남는다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);

        SharingReallowResponse response = sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID);

        assertThat(response).isEqualTo(new SharingReallowResponse(ASSIGNMENT_ID, SharingStatus.SHARING, List.of(
                SharedItemName.of(InformationType.MEDICATION, "혈압약"),
                SharedItemName.of(InformationType.MEDICATION, "당뇨약"))));

        // 항목마다 직접 철회 기록 1행 + 새 ACTIVE 1행 (기존 행을 되살리지 않고 새로 만듦)
        List<RequestInformationPermission> permissions = permissionRepository.findAll();
        assertThat(permissions).filteredOn(p -> p.getStatus() == PermissionStatus.ACTIVE)
                .extracting(RequestInformationPermission::getInformationId)
                .containsExactlyInAnyOrder(medication, disease);
        assertThat(permissions).filteredOn(p -> p.getRevokeReason() == RevokeReason.OWNER_REVOKED).hasSize(2);

        // 이력: 철회 2건 + 다시 허용 GRANTED 2건 (수행자 = 정보 주인)
        assertThat(accessLogRepository.findAll()).filteredOn(log -> log.getEventType() == AccessEventType.GRANTED)
                .extracting(InformationAccessLog::getActorUserId)
                .containsExactly(OWNER_ID, OWNER_ID);

        // 다시 허용은 알림 이벤트를 발행하지 않음 (철회의 1건만)
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).hasSize(1);
    }

    @Test
    void 다시_허용한_뒤_다시_철회할_수_있다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);
        sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID);

        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID); // 최신 행(다시 허용으로 만든 ACTIVE)을 철회

        assertThat(permissionRepository.findAll()).allSatisfy(permission ->
                assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED)); // 항목마다 철회 기록 2행
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).hasSize(2);
    }

    @Test
    void 섞인_카드를_전체_철회한_뒤_다시_허용하면_전에_철회한_항목까지_모두_열린다() {
        // 혈압약만 직접 철회된 상태에서 당뇨약은 공유 중(섞인 카드). 철회 뒤 요청 수정으로 당뇨약이 새로 공개된 경우와 같음
        Long medicationActiveId = activeIdOf(medication);
        transactionTemplate.executeWithoutResult(status -> permissionRepository.findById(medicationActiveId).orElseThrow()
                .revoke(RevokeReason.OWNER_REVOKED, GRANTED_AT.plusHours(1)));

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED); // 섞인 카드는 공유 중이라 바로 다시 허용 불가

        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);                 // 남은 당뇨약까지 전체 철회
        SharingReallowResponse response = sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID);

        // 최신 권한이 모두 직접 철회라, 전에 철회한 혈압약과 이번에 철회한 당뇨약이 함께 열림
        assertThat(response.sharedItems()).containsExactly(
                SharedItemName.of(InformationType.MEDICATION, "혈압약"),
                SharedItemName.of(InformationType.MEDICATION, "당뇨약"));
        assertThat(permissionRepository.findAll()).filteredOn(p -> p.getStatus() == PermissionStatus.ACTIVE)
                .extracting(RequestInformationPermission::getInformationId)
                .containsExactlyInAnyOrder(medication, disease);
    }

    @Test
    void 이미_공유_중인_카드는_409이고_권한이_늘지_않는다() {
        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);

        assertThat(permissionRepository.findAll()).hasSize(2);
        assertThat(accessLogRepository.findAll()).isEmpty();
    }

    @Test
    void 철회한_항목을_모두_삭제했으면_409_NOTHING_TO_REALLOW다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);
        deleteItem(medication);
        deleteItem(disease);

        assertReallowError(SafetyCardErrorCode.SHARING_NOTHING_TO_REALLOW);
        assertThat(permissionRepository.findAll()).noneMatch(permission -> permission.getStatus() == PermissionStatus.ACTIVE);
    }

    @Test
    void 철회_후_일부_항목을_삭제했으면_남은_항목만_다시_연다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);
        deleteItem(disease);

        SharingReallowResponse response = sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID);

        assertThat(response.sharedItems()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "혈압약"));
        assertThat(permissionRepository.findAll()).filteredOn(p -> p.getStatus() == PermissionStatus.ACTIVE)
                .extracting(RequestInformationPermission::getInformationId)
                .containsExactly(medication);
    }

    @Test
    void 요청이_끝났으면_409이고_권한이_바뀌지_않는다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);
        givenRequest(CareRequestStatus.COMPLETED);

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        assertThat(permissionRepository.findAll()).noneMatch(permission -> permission.getStatus() == PermissionStatus.ACTIVE);
    }

    // === 도우미 ===

    /** 이 카드로 다시 허용하면 이 오류 코드인지 */
    private void assertReallowError(SafetyCardErrorCode expected) {
        assertThatThrownBy(() -> sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    /** C 요청: 이 카드의 지정이 지금 담당자의 지정인 일반 요청. 종료 시각은 서비스 시계 기준 지금보다 2시간 뒤 */
    private void givenRequest(CareRequestStatus status) {
        LocalDateTime now = LocalDateTime.now(clock);
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, status, RequestType.GENERAL,
                now.minusHours(1), now.plusHours(2), SITUATION_ID, ASSIGNEE_ID, now.minusDays(1), HelpType.HOSPITAL)));
    }

    /** 정보 주인의 항목 저장 */
    private Long saveItem(String title) {
        return safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.MEDICATION, title, "아침 1정")).getId();
    }

    /** 이 항목의 지금 공유 중(ACTIVE) 권한 ID */
    private Long activeIdOf(Long informationId) {
        return permissionRepository.findAll().stream()
                .filter(p -> informationId.equals(p.getInformationId()) && p.getStatus() == PermissionStatus.ACTIVE)
                .findFirst().orElseThrow()
                .getId();
    }

    /** 이 카드의 공유 중(ACTIVE) 권한 저장 (수락으로 열린 상태) */
    private void saveActive(Long informationId) {
        permissionRepository.save(RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, InformationType.MEDICATION, GRANTED_AT, null));
    }

    /**
     * 항목 삭제와 같은 순서: 권한의 information_id를 비운 뒤 원본 삭제 (ERD 9.5)
     * clearInformationId는 직접 선언한 수정 쿼리라 트랜잭션이 자동으로 붙지 않아, 한 트랜잭션으로 묶어 실행합니다.
     */
    private void deleteItem(Long informationId) {
        transactionTemplate.executeWithoutResult(status -> {
            permissionRepository.clearInformationId(informationId);
            safetyInformationRepository.deleteById(informationId);
        });
    }
}
