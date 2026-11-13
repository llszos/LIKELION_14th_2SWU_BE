package com.gyeot.domain.safetycard.service;

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
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 항목 삭제가 실제 DB에서 ERD 9.5 순서대로 한 트랜잭션으로 처리되는지 확인합니다. (#38)
 *
 * - 서비스(SafetyInformationService.delete)부터 access 영역 권한 정리, DB까지 실제로 이어서 실행합니다.
 * - 권한과 이력은 C 이벤트 없이 저장소로 직접 만들어 둡니다. (부여 흐름은 #42 통합 테스트에서 확인)
 * - C의 공유용 요청 조회는 요청 데이터를 만들지 않으려고 가짜로 바꿉니다. 실패를 일으켜 롤백을 확인하는 데도 씁니다.
 * - 발행된 PermissionRevokedEvent는 @RecordApplicationEvents로 모아 확인합니다.
 * - 각 테스트는 커밋되므로 끝나면 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
class SafetyInformationDeleteIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;    // 정보 주인
    private static final Long ASSIGNEE_ID = 20L; // 요청 1번의 담당자
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final LocalDateTime OLD_REVOKED_AT = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Autowired private SafetyInformationService safetyInformationService;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private ApplicationEvents applicationEvents;

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;

    private Long allergyId; // 삭제할 항목
    private Long petId;     // 삭제하지 않는 다른 항목

    // 알레르기 항목의 권한 3개와 반려동물 항목의 권한 1개
    private Long activeId;     // 요청 1번(지정 7번), 수락 후
    private Long pendingId;    // 요청 2번(지정 9번), 수락 전
    private Long oldRevokedId; // 요청 3번(지정 5번), 이미 직접 철회됨
    private Long otherItemId;  // 요청 1번(지정 7번)의 반려동물 권한

    @BeforeEach
    void setUp() {
        allergyId = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.ALLERGY, "땅콩", "호흡 곤란")).getId();
        petId = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.PET, "초코", "사료 하루 두 번")).getId();

        activeId = savePermission(1L, 7L, allergyId, InformationType.ALLERGY, PermissionStatus.ACTIVE);
        pendingId = savePermission(2L, 9L, allergyId, InformationType.ALLERGY, PermissionStatus.PENDING);
        oldRevokedId = savePermission(3L, 5L, allergyId, InformationType.ALLERGY, PermissionStatus.REVOKED);
        otherItemId = savePermission(1L, 7L, petId, InformationType.PET, PermissionStatus.ACTIVE);

        // 수락 때 남은 부여 이력 (삭제 후에도 그대로 남아야 함)
        accessLogRepository.save(InformationAccessLog.create(activeId, OWNER_ID, ASSIGNEE_ID,
                allergyId, InformationType.ALLERGY, AccessEventType.GRANTED, GRANTED_AT));

        // C 조회: 요청 1번의 현재 지정은 7번, 담당자는 20번
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(1L, request(1L, 7L, ASSIGNEE_ID)));
    }

    @AfterEach
    void cleanUp() {
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 공유_중인_항목을_삭제하면_권한을_회수하고_ID를_비우고_항목을_지운다() {
        safetyInformationService.delete(OWNER_ID, allergyId);

        // 항목 행 삭제 (다른 항목은 그대로)
        assertThat(safetyInformationRepository.findById(allergyId)).isEmpty();
        assertThat(safetyInformationRepository.findById(petId)).isPresent();

        // 열린 권한(ACTIVE, PENDING)은 ITEM_DELETED로 회수, 같은 시각
        RequestInformationPermission active = permission(activeId);
        RequestInformationPermission pending = permission(pendingId);
        assertThat(List.of(active, pending)).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ITEM_DELETED);
            assertThat(permission.getRevokedAt()).isNotNull();
        });
        assertThat(pending.getRevokedAt()).isEqualTo(active.getRevokedAt());

        // 이미 철회된 과거 권한은 처음 이유와 시각 유지
        RequestInformationPermission oldRevoked = permission(oldRevokedId);
        assertThat(oldRevoked.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED);
        assertThat(oldRevoked.getRevokedAt()).isEqualTo(OLD_REVOKED_AT);

        // 삭제한 항목의 권한은 과거 권한까지 모두 information_id가 NULL, 유형은 유지 (ERD 9.5 순서 4)
        assertThat(List.of(active, pending, oldRevoked)).allSatisfy(permission -> {
            assertThat(permission.getInformationId()).isNull();
            assertThat(permission.getInformationType()).isEqualTo(InformationType.ALLERGY);
        });

        // 다른 항목의 권한은 그대로
        RequestInformationPermission otherItem = permission(otherItemId);
        assertThat(otherItem.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(otherItem.getInformationId()).isEqualTo(petId);
    }

    @Test
    void ACTIVE였던_권한만_회수_이력이_남고_기존_이력은_그대로다() {
        safetyInformationService.delete(OWNER_ID, allergyId);

        List<InformationAccessLog> logs = accessLogRepository.findAll();

        // 부여 이력 1건 + 회수 이력 1건 (PENDING은 이력 없음)
        assertThat(logs).extracting(InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(AccessEventType.GRANTED, AccessEventType.AUTO_REVOKED);

        InformationAccessLog revokedLog = logs.stream()
                .filter(log -> log.getEventType() == AccessEventType.AUTO_REVOKED)
                .findFirst().orElseThrow();
        assertThat(revokedLog.getPermissionId()).isEqualTo(activeId);
        assertThat(revokedLog.getActorUserId()).isEqualTo(OWNER_ID);    // 항목 삭제는 정보 주인이 수행자
        assertThat(revokedLog.getInformationId()).isEqualTo(allergyId); // 이력의 항목 ID는 스냅샷이라 그대로
        assertThat(revokedLog.getOccurredAt()).isEqualTo(permission(activeId).getRevokedAt());
    }

    @Test
    void ACTIVE였던_권한의_담당자에게만_알림_이벤트를_한_건_발행한다() {
        safetyInformationService.delete(OWNER_ID, allergyId);

        // 요청 1번만 ACTIVE였음 (요청 2번은 수락 전, 요청 3번은 이미 철회)
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).containsExactly(
                new PermissionRevokedEvent(1L, 7L, OWNER_ID, ASSIGNEE_ID, RevokeReason.ITEM_DELETED));
    }

    @Test
    void 삭제_중간에_실패하면_권한_회수와_이력과_항목_삭제가_모두_되돌아간다() {
        // 회수 다음 단계(알림용 C 조회)에서 실패시킴
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenThrow(new IllegalStateException("C 조회 실패"));

        assertThatThrownBy(() -> safetyInformationService.delete(OWNER_ID, allergyId))
                .isInstanceOf(IllegalStateException.class);

        // 항목, 권한 상태, information_id, 이력 모두 삭제 전 그대로
        assertThat(safetyInformationRepository.findById(allergyId)).isPresent();
        RequestInformationPermission active = permission(activeId);
        assertThat(active.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(active.getInformationId()).isEqualTo(allergyId);
        assertThat(permission(pendingId).getStatus()).isEqualTo(PermissionStatus.PENDING);
        assertThat(accessLogRepository.findAll()).extracting(InformationAccessLog::getEventType)
                .containsExactly(AccessEventType.GRANTED);
    }

    @Test
    void 다른_사람_항목은_404이고_아무것도_바뀌지_않는다() {
        assertThatThrownBy(() -> safetyInformationService.delete(99L, allergyId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SAFETY_INFO_NOT_FOUND);

        assertThat(safetyInformationRepository.findById(allergyId)).isPresent();
        assertThat(permission(activeId).getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).isEmpty();
    }

    // === 도우미 ===

    /** 상태별 권한을 만들어 저장하고 ID를 돌려줍니다. */
    private Long savePermission(Long requestId, Long assignmentId, Long informationId,
                                InformationType type, PermissionStatus status) {
        RequestInformationPermission permission =
                RequestInformationPermission.createPending(requestId, assignmentId, informationId, type);

        if (status != PermissionStatus.PENDING) {
            permission.activate(GRANTED_AT, null); // ACTIVE와 REVOKED는 한 번 수락된 권한
        }
        if (status == PermissionStatus.REVOKED) {
            permission.revoke(RevokeReason.OWNER_REVOKED, OLD_REVOKED_AT);
        }
        return permissionRepository.save(permission).getId();
    }

    private RequestInformationPermission permission(Long id) {
        return permissionRepository.findById(id).orElseThrow();
    }

    // C의 공유용 요청 정보 (현재 지정 ID와 담당자 ID만 쓰임)
    private static SharingRequestInfo request(Long requestId, Long assignmentId, Long assigneeId) {
        return new SharingRequestInfo(requestId, OWNER_ID, assignmentId, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                null, null, null, assigneeId, GRANTED_AT, HelpType.HOSPITAL);
    }
}
