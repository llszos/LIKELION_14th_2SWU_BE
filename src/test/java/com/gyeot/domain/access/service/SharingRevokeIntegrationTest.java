package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 공유 직접 철회가 실제 DB에서 한 트랜잭션으로 처리되는지 확인합니다. (F-30, #46)
 *
 * - 진입점(SharingRevokeService)부터 쓰기 트랜잭션, PermissionWriter, DB까지 실제로 이어서 실행합니다.
 *   요청 ID 조회와 쓰기가 다른 트랜잭션으로 나뉘어 있어, 테스트 메서드에는 트랜잭션을 걸지 않습니다. (각 테스트는 커밋되므로 끝나면 직접 지움)
 * - 권한과 항목은 저장소로 직접 만들어 둡니다. C 요청 조회는 요청 데이터를 만들지 않으려고 가짜로 바꿉니다.
 * - 관계(A)와 상황 참여(B)도 가짜로 바꿔 canReallow를 정합니다. (실제 확인 규칙은 SharingCardReaderTest)
 * - 발행된 PermissionRevokedEvent는 @RecordApplicationEvents로 모아 확인합니다.
 *   동기 이벤트 처리 실패는 아직 받는 쪽(B 알림)이 없어, 테스트용 리스너가 실패하게 만들어 롤백을 확인합니다.
 * - C 요청 행 잠금과 동시 철회, 동시 삭제 경합은 C 잠금 계약 반영 뒤 별도 동시성 테스트에서 확인합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
class SharingRevokeIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자, 로그인한 사용자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long REQUEST_ID = 31L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime OLD_REVOKED_AT = LocalDateTime.of(2026, 11, 1, 10, 0); // 과거 직접 철회 시각

    @Autowired private SharingRevokeService sharingRevokeService;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private FailingRevokedListener failingRevokedListener;
    @Autowired private Clock clock; // 서비스와 같은 시계 (Asia/Seoul). JVM 기본 시간대로 지금을 구하면 CI(UTC)에서 9시간 어긋남

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;         // C 요청 (진행 중인 일반 요청)
    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;  // A 관계 (canReallow)
    @MockitoBean private SituationParticipantReader situationParticipantReader;     // B 상황 참여 (canReallow)

    // 카드(요청 31, 지정 40)의 권한
    private Long oldRevokedId; // 혈압약: 과거 직접 철회 (다시 허용 전 기록)
    private Long reallowedId;  // 혈압약: 다시 허용으로 만든 ACTIVE (최신)
    private Long activeId;     // 당뇨약: ACTIVE

    @BeforeEach
    void setUp() {
        Long medication = saveItem("혈압약");
        Long disease = saveItem("당뇨약");

        oldRevokedId = savePermission(medication, true);
        reallowedId = savePermission(medication, false);
        activeId = savePermission(disease, false);

        givenRequest(CareRequestStatus.IN_PROGRESS);
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any())).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        failingRevokedListener.fail = false; // 컨텍스트를 함께 쓰는 다음 테스트에 영향이 없게 되돌림
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 공유_중_카드를_철회하면_최신_ACTIVE_권한이_직접_철회되고_이력과_이벤트가_남는다() {
        SharingRevokeResponse response = sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);

        assertThat(response).isEqualTo(new SharingRevokeResponse(ASSIGNMENT_ID, SharingStatus.REVOKED, true));

        // 최신 ACTIVE 두 개는 OWNER_REVOKED, 같은 시각
        RequestInformationPermission reallowed = permission(reallowedId);
        RequestInformationPermission active = permission(activeId);
        assertThat(List.of(reallowed, active)).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED);
        });
        assertThat(active.getRevokedAt()).isEqualTo(reallowed.getRevokedAt());

        // 과거 철회 기록은 처음 시각 그대로
        assertThat(permission(oldRevokedId).getRevokedAt()).isEqualTo(OLD_REVOKED_AT);

        // 이력: 이번에 끝낸 권한마다 OWNER_REVOKED 1건, 수행자 = 정보 주인
        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getEventType,
                        InformationAccessLog::getActorUserId)
                .containsExactlyInAnyOrder(
                        tuple(reallowedId, AccessEventType.OWNER_REVOKED, OWNER_ID),
                        tuple(activeId, AccessEventType.OWNER_REVOKED, OWNER_ID));

        // 항목이 2개여도 카드당 이벤트 1건
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).containsExactly(
                new PermissionRevokedEvent(REQUEST_ID, ASSIGNMENT_ID, OWNER_ID, ASSIGNEE_ID, RevokeReason.OWNER_REVOKED));
    }

    @Test
    void 같은_카드를_다시_철회하면_200이고_이력과_이벤트가_늘지_않는다() {
        sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);
        LocalDateTime firstRevokedAt = permission(activeId).getRevokedAt();

        SharingRevokeResponse again = sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID);

        assertThat(again).isEqualTo(new SharingRevokeResponse(ASSIGNMENT_ID, SharingStatus.REVOKED, true));
        assertThat(permission(activeId).getRevokedAt()).isEqualTo(firstRevokedAt); // 처음 철회 시각 유지
        assertThat(accessLogRepository.findAll()).hasSize(2);                     // 첫 철회의 2건 그대로
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).hasSize(1);
    }

    @Test
    void 이벤트_처리가_실패하면_권한_회수와_이력이_모두_되돌아간다() {
        // 같은 트랜잭션에서 동기로 실행되는 받는 쪽 처리(향후 B 알림 저장)가 실패한 경우
        failingRevokedListener.fail = true;

        assertThatThrownBy(() -> sharingRevokeService.revoke(OWNER_ID, ASSIGNMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("알림 처리 실패");

        assertThat(permission(reallowedId).getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(permission(activeId).getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(accessLogRepository.findAll()).isEmpty();
    }

    @Test
    void 끝난_요청의_카드면_404이고_아무것도_바뀌지_않는다() {
        givenRequest(CareRequestStatus.COMPLETED);

        assertNotFound(ASSIGNMENT_ID);
        assertThat(permission(activeId).getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(accessLogRepository.findAll()).isEmpty();
        assertThat(applicationEvents.stream(PermissionRevokedEvent.class)).isEmpty();
    }

    @Test
    void 권한이_없는_카드면_404다() {
        assertNotFound(99L);
    }

    // === 도우미 ===

    /** 테스트용 리스너: fail이 true면 권한 종료 이벤트를 받을 때 실패 (동기 처리라 철회 트랜잭션이 롤백됨) */
    static class FailingRevokedListener {

        volatile boolean fail; // 테스트마다 켜고 끔 (cleanUp에서 false로 되돌림)

        @EventListener
        void onRevoked(PermissionRevokedEvent event) {
            if (fail) {
                throw new IllegalStateException("알림 처리 실패");
            }
        }
    }

    /** 테스트용 리스너를 빈으로 등록 (이 테스트 클래스에서만 쓰는 설정) */
    @TestConfiguration
    static class ListenerConfig {

        @Bean
        FailingRevokedListener failingRevokedListener() {
            return new FailingRevokedListener();
        }
    }

    /** 이 카드 ID로 철회하면 404 SHARING_NOT_FOUND인지 */
    private void assertNotFound(Long assignmentId) {
        assertThatThrownBy(() -> sharingRevokeService.revoke(OWNER_ID, assignmentId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARING_NOT_FOUND);
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

    /** 카드의 권한 저장. revoked면 과거에 직접 철회한 기록 (저장 순서 = ID 순서라 나중에 저장한 행이 최신) */
    private Long savePermission(Long informationId, boolean revoked) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, InformationType.MEDICATION, OLD_REVOKED_AT.minusDays(1), null);
        if (revoked) {
            permission.revoke(RevokeReason.OWNER_REVOKED, OLD_REVOKED_AT);
        }
        return permissionRepository.save(permission).getId();
    }

    /** DB에서 다시 읽은 권한 (테스트 메서드에 트랜잭션이 없어 커밋된 최신 상태) */
    private RequestInformationPermission permission(Long id) {
        return permissionRepository.findById(id).orElseThrow();
    }
}
