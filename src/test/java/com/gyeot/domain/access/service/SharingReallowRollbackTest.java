package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
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
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 공유 다시 허용 중 이력 저장이 실패하면 새 권한이 남지 않고 기존 철회 기록이 그대로인지 확인합니다. (F-30, #46)
 *
 * - 접근 이력 저장소를 실패하는 가짜로 바꿔야 해서, 다른 테스트에 영향을 주지 않도록 클래스를 따로 두었습니다.
 *   (CareRequestPermissionRevokeRollbackTest와 같은 방식)
 * - 이력 저장소가 가짜라 철회 API로 철회 상태를 만들 수 없어, 저장소로 바로 직접 철회(OWNER_REVOKED) 상태를 만들어 둡니다.
 * - 다시 허용은 새 ACTIVE 권한을 먼저 저장한 뒤 GRANTED 이력을 저장하므로, 이력 저장 실패 시 이미 INSERT된 권한까지 되돌려져야 합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SharingReallowRollbackTest {

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
    private static final LocalDateTime REVOKED_AT = GRANTED_AT.plusHours(1);              // 직접 철회 시각

    @Autowired private SharingReallowService sharingReallowService;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul)

    @MockitoBean private InformationAccessLogRepository accessLogRepository;       // GRANTED 이력 저장을 실패시킴
    @MockitoBean private CareRequestSharingReader careRequestSharingReader;         // C 요청 (진행 중인 일반 요청)
    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;  // A 관계 (다시 열 수 있는 상태)
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B 상황 참여 (다시 열 수 있는 상태)

    @BeforeEach
    void setUp() {
        // 직접 철회된 카드: 혈압약, 당뇨약 모두 OWNER_REVOKED
        saveRevoked(saveItem("혈압약"));
        saveRevoked(saveItem("당뇨약"));

        LocalDateTime now = LocalDateTime.now(clock);
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                now.minusHours(1), now.plusHours(2), SITUATION_ID, ASSIGNEE_ID, now.minusDays(1), HelpType.HOSPITAL)));
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any())).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);

        when(accessLogRepository.saveAll(anyList())).thenThrow(new IllegalStateException("이력 저장 실패"));
    }

    @AfterEach
    void cleanUp() {
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 다시_허용_중_이력_저장이_실패하면_새_ACTIVE_권한이_남지_않고_철회_기록이_그대로다() {
        assertThatThrownBy(() -> sharingReallowService.reallow(OWNER_ID, ASSIGNMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이력 저장 실패");

        // 먼저 INSERT된 새 ACTIVE 권한까지 되돌려져, 철회 기록 2행만 처음 상태 그대로 남음
        List<RequestInformationPermission> permissions = permissionRepository.findAll();
        assertThat(permissions).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED);
            assertThat(permission.getRevokedAt()).isEqualTo(REVOKED_AT);
        });
    }

    // === 도우미 ===

    /** 정보 주인의 항목 저장 */
    private Long saveItem(String title) {
        return safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.MEDICATION, title, "아침 1정")).getId();
    }

    /** 이 카드의 직접 철회된 권한 저장 (수락으로 열렸다가 정보 주인이 철회한 상태) */
    private void saveRevoked(Long informationId) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, InformationType.MEDICATION, GRANTED_AT, null);
        permission.revoke(RevokeReason.OWNER_REVOKED, REVOKED_AT);
        permissionRepository.save(permission);
    }
}
