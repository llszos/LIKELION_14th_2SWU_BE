package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 Repository를 Mock으로 바꿔 권한 공통 처리 규칙만 확인합니다. (#42 부여, #43 회수, #38 항목 삭제) */
class PermissionWriterTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 0);

    private RequestInformationPermissionRepository permissionRepository;
    private InformationAccessLogRepository accessLogRepository;
    private SafetyInformationRepository safetyInformationRepository;
    private PermissionWriter writer;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        accessLogRepository = mock(InformationAccessLogRepository.class);
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        writer = new PermissionWriter(permissionRepository, accessLogRepository, safetyInformationRepository);
    }

    // === 항목 확인 ===

    @Test
    void 빈_목록과_null은_조회하지_않고_빈_결과를_돌려준다() {
        assertThat(writer.loadOwnedItemTypes(OWNER_ID, List.of())).isEmpty();
        assertThat(writer.loadOwnedItemTypes(OWNER_ID, null)).isEmpty();
        verify(safetyInformationRepository, never()).findAllById(any());
    }

    @Test
    void 모두_본인_항목이면_항목_ID별_유형을_돌려준다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(
                item(10L, OWNER_ID, InformationType.ALLERGY), item(11L, OWNER_ID, InformationType.PET)));

        Map<Long, InformationType> types = writer.loadOwnedItemTypes(OWNER_ID, List.of(10L, 11L));

        assertThat(types).containsExactlyInAnyOrderEntriesOf(
                Map.of(10L, InformationType.ALLERGY, 11L, InformationType.PET));
    }

    @Test
    void 없는_항목이_있으면_전체를_거부한다() {
        when(safetyInformationRepository.findAllById(any()))
                .thenReturn(List.of(item(10L, OWNER_ID, InformationType.ALLERGY)));

        assertInvalidSelection(List.of(10L, 99L));
    }

    @Test
    void 다른_사람_항목이_있으면_전체를_거부한다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(
                item(10L, OWNER_ID, InformationType.ALLERGY), item(20L, OTHER_ID, InformationType.ALLERGY)));

        assertInvalidSelection(List.of(10L, 20L));
    }

    @Test
    void 중복_ID나_null이_있으면_조회하지_않고_거부한다() {
        assertInvalidSelection(List.of(10L, 10L));
        assertInvalidSelection(Arrays.asList(10L, null));
        verify(safetyInformationRepository, never()).findAllById(any());
    }

    // === 재확인 수락용 항목 거르기 ===

    @Test
    void 지금_있고_본인_것인_항목만_고른_순서대로_남긴다() {
        // 11번은 삭제됨(조회 안 됨), 20번은 다른 사람 것
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(
                item(12L, OWNER_ID, InformationType.PET), item(10L, OWNER_ID, InformationType.ALLERGY),
                item(20L, OTHER_ID, InformationType.ALLERGY)));

        assertThat(writer.filterOwnedItemIds(OWNER_ID, List.of(12L, 11L, 20L, 10L))).containsExactly(12L, 10L);
    }

    @Test
    void 거를_때_null과_중복은_빼고_예외를_던지지_않는다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(item(10L, OWNER_ID, InformationType.ALLERGY)));

        assertThat(writer.filterOwnedItemIds(OWNER_ID, Arrays.asList(10L, null, 10L))).containsExactly(10L);
    }

    @Test
    void 거를_목록이_비어_있으면_조회하지_않는다() {
        assertThat(writer.filterOwnedItemIds(OWNER_ID, List.of())).isEmpty();
        assertThat(writer.filterOwnedItemIds(OWNER_ID, null)).isEmpty();
        verify(safetyInformationRepository, never()).findAllById(any());
    }

    // === 공유 예정 권한 생성 ===

    @Test
    void 공개_항목마다_PENDING_권한을_만든다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(
                item(10L, OWNER_ID, InformationType.ALLERGY), item(11L, OWNER_ID, InformationType.PET)));
        returnSavedPermissions();

        List<RequestInformationPermission> created = writer.createPending(1L, 7L, OWNER_ID, List.of(10L, 11L));

        assertThat(created).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getRequestId()).isEqualTo(1L);
            assertThat(permission.getAssignmentId()).isEqualTo(7L);
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.PENDING);
            assertThat(permission.getGrantedAt()).isNull();
        });
        assertThat(created).extracting(RequestInformationPermission::getInformationId, RequestInformationPermission::getInformationType)
                .containsExactly(tuple(10L, InformationType.ALLERGY), tuple(11L, InformationType.PET));
    }

    @Test
    void 공개_항목이_0개면_아무것도_만들지_않는다() {
        assertThat(writer.createPending(1L, 7L, OWNER_ID, List.of())).isEmpty();
        verify(permissionRepository, never()).saveAll(any());
    }

    @Test
    void 이미_끝나지_않은_권한이_있는_항목은_다시_만들지_않는다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(
                item(10L, OWNER_ID, InformationType.ALLERGY), item(11L, OWNER_ID, InformationType.PET)));
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any()))
                .thenReturn(List.of(pending(100L, 10L)));    // 10번은 이미 있음 (같은 이벤트가 다시 온 경우)
        returnSavedPermissions();

        List<RequestInformationPermission> created = writer.createPending(1L, 7L, OWNER_ID, List.of(10L, 11L));

        assertThat(created).extracting(RequestInformationPermission::getInformationId).containsExactly(11L);
    }

    @Test
    void 항목_확인에_실패하면_권한을_만들지_않는다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> writer.createPending(1L, 7L, OWNER_ID, List.of(10L)))
                .isInstanceOf(BusinessException.class);
        verify(permissionRepository, never()).saveAll(any());
    }

    // === 바로 활성화된 권한 생성 (수락 후 추가) ===

    @Test
    void 수락_후_추가는_ACTIVE로_만들고_항목마다_GRANTED_이력을_남긴다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(item(10L, OWNER_ID, InformationType.ALLERGY)));
        when(permissionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<RequestInformationPermission> saved = invocation.getArgument(0);
            saved.forEach(permission -> ReflectionTestUtils.setField(permission, "id", 200L)); // 저장 후 ID가 생긴 것처럼
            return saved;
        });

        List<RequestInformationPermission> created =
                writer.createActive(1L, 7L, OWNER_ID, 20L, List.of(10L), NOW, NOW.plusHours(24));

        assertThat(created).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
            assertThat(permission.getGrantedAt()).isEqualTo(NOW);
            assertThat(permission.getExpiresAt()).isEqualTo(NOW.plusHours(24));
        });
        assertThat(savedLogs()).singleElement().satisfies(log -> {
            assertThat(log.getPermissionId()).isEqualTo(200L); // 저장된 권한 ID로 기록
            assertThat(log.getActorUserId()).isEqualTo(20L);
            assertThat(log.getEventType()).isEqualTo(AccessEventType.GRANTED);
        });
    }

    @Test
    void 수락_후_추가도_이미_끝나지_않은_권한이_있는_항목은_만들지_않는다() {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(item(10L, OWNER_ID, InformationType.ALLERGY)));
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any())).thenReturn(List.of(active(100L, 10L)));
        returnSavedPermissions();

        assertThat(writer.createActive(1L, 7L, OWNER_ID, 20L, List.of(10L), NOW, null)).isEmpty();
        assertThat(savedLogs()).isEmpty();
    }

    // === 활성화 (수락) ===

    @Test
    void PENDING_권한만_활성화하고_항목마다_GRANTED_이력을_남긴다() {
        RequestInformationPermission pending = pending(100L, 10L);
        RequestInformationPermission alreadyActive = active(101L, 11L);
        LocalDateTime activeGrantedAt = alreadyActive.getGrantedAt();
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any()))
                .thenReturn(List.of(pending, alreadyActive));

        List<RequestInformationPermission> activated = writer.activatePending(7L, OWNER_ID, 20L, NOW, null);

        assertThat(activated).containsExactly(pending);
        assertThat(pending.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(pending.getGrantedAt()).isEqualTo(NOW);
        assertThat(pending.getExpiresAt()).isNull();
        assertThat(alreadyActive.getGrantedAt()).isEqualTo(activeGrantedAt); // 이미 ACTIVE는 그대로

        InformationAccessLog log = savedLogs().get(0);
        assertThat(savedLogs()).hasSize(1);
        assertThat(log.getPermissionId()).isEqualTo(100L);
        assertThat(log.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(log.getActorUserId()).isEqualTo(20L);           // 수락한 담당자
        assertThat(log.getInformationId()).isEqualTo(10L);
        assertThat(log.getEventType()).isEqualTo(AccessEventType.GRANTED);
        assertThat(log.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void 기한이_있으면_활성화할_때_함께_저장한다() {
        RequestInformationPermission pending = pending(100L, 10L);
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any())).thenReturn(List.of(pending));

        writer.activatePending(7L, OWNER_ID, 20L, NOW, NOW.plusHours(24));

        assertThat(pending.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    }

    @Test
    void 활성화할_PENDING이_없으면_이력도_남지_않는다() {
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any())).thenReturn(List.of(active(101L, 11L)));

        assertThat(writer.activatePending(7L, OWNER_ID, 20L, NOW, null)).isEmpty();
        assertThat(savedLogs()).isEmpty();
    }

    // === 회수 공통 처리 ===

    @Test
    void ACTIVE_권한은_회수되고_AUTO_REVOKED_이력이_남는다() {
        RequestInformationPermission active = active(100L, 10L);

        writer.revokeByRequestEvent(List.of(active), RevokeReason.REQUEST_ENDED, OWNER_ID, NOW);

        assertRevoked(active, RevokeReason.REQUEST_ENDED);
        List<InformationAccessLog> logs = savedLogs();
        assertThat(logs).hasSize(1);
        InformationAccessLog log = logs.get(0);
        assertThat(log.getPermissionId()).isEqualTo(100L);
        assertThat(log.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(log.getActorUserId()).isNull();                 // 시스템 처리
        assertThat(log.getInformationId()).isEqualTo(10L);
        assertThat(log.getInformationType()).isEqualTo(InformationType.ALLERGY);
        assertThat(log.getEventType()).isEqualTo(AccessEventType.AUTO_REVOKED);
        assertThat(log.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void PENDING_권한은_회수되지만_이력은_남지_않는다() {
        RequestInformationPermission pending = pending(100L, 10L);

        writer.revokeByRequestEvent(List.of(pending), RevokeReason.ASSIGNMENT_CLOSED, OWNER_ID, NOW);

        assertRevoked(pending, RevokeReason.ASSIGNMENT_CLOSED);
        assertThat(savedLogs()).isEmpty();
    }

    @Test
    void 공개_항목_제외는_정보_주인이_수행자로_남는다() {
        RequestInformationPermission active = active(100L, 10L);

        writer.revokeByRequestEvent(List.of(active), RevokeReason.SHARING_EXCLUDED, OWNER_ID, NOW);

        assertThat(savedLogs()).singleElement()
                .extracting(InformationAccessLog::getActorUserId).isEqualTo(OWNER_ID);
    }

    @Test
    void 이미_회수된_권한은_그대로_두고_이력도_남기지_않는다() {
        RequestInformationPermission revoked = active(100L, 10L);
        LocalDateTime earlier = NOW.minusDays(1);
        revoked.revoke(RevokeReason.OWNER_REVOKED, earlier);

        writer.revokeByRequestEvent(List.of(revoked), RevokeReason.REQUEST_ENDED, OWNER_ID, NOW);

        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED); // 처음 끝난 이유와 시각 유지
        assertThat(revoked.getRevokedAt()).isEqualTo(earlier);
        assertThat(savedLogs()).isEmpty();
    }

    @Test
    void 지정_단위_회수는_그_지정의_끝나지_않은_권한을_모두_끝낸다() {
        RequestInformationPermission pending = pending(100L, 10L);
        RequestInformationPermission active = active(101L, 11L);
        when(permissionRepository.findAllByAssignmentIdAndStatusIn(any(), any())).thenReturn(List.of(pending, active));

        writer.revokeAssignment(7L, RevokeReason.ASSIGNMENT_CLOSED, OWNER_ID, NOW);

        assertRevoked(pending, RevokeReason.ASSIGNMENT_CLOSED);
        assertRevoked(active, RevokeReason.ASSIGNMENT_CLOSED);
        assertThat(savedLogs()).extracting(InformationAccessLog::getPermissionId).containsExactly(101L); // ACTIVE만 이력
        verify(permissionRepository).findAllByAssignmentIdAndStatusIn(7L,
                List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE));
    }

    @Test
    void 요청_단위_회수는_지정과_상관없이_그_요청의_끝나지_않은_권한을_모두_끝낸다() {
        // 대상 변경 전 지정(7번)에 남은 PENDING과 지금 지정(8번)의 ACTIVE가 같은 요청(1번)에 있음
        RequestInformationPermission previousPending = pending(100L, 10L);
        RequestInformationPermission currentActive = active(101L, 11L);
        ReflectionTestUtils.setField(currentActive, "assignmentId", 8L);
        when(permissionRepository.findAllByRequestIdAndStatusIn(any(), any()))
                .thenReturn(List.of(previousPending, currentActive));

        writer.revokeRequest(1L, RevokeReason.REQUEST_ENDED, OWNER_ID, NOW);

        assertRevoked(previousPending, RevokeReason.REQUEST_ENDED);
        assertRevoked(currentActive, RevokeReason.REQUEST_ENDED);
        assertThat(savedLogs()).extracting(InformationAccessLog::getPermissionId).containsExactly(101L); // ACTIVE만 이력

        // 요청 기준으로 PENDING, ACTIVE만 조회 (지정 기준 조회는 쓰지 않음)
        verify(permissionRepository).findAllByRequestIdAndStatusIn(1L,
                List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE));
        verify(permissionRepository, never()).findAllByAssignmentIdAndStatusIn(any(), any());
    }

    @Test
    void 요청_단위_회수할_권한이_없으면_아무것도_바뀌지_않는다() {
        // 이미 모두 회수됐거나(같은 이벤트 재처리) 공개 항목이 0개인 요청
        when(permissionRepository.findAllByRequestIdAndStatusIn(any(), any())).thenReturn(List.of());

        writer.revokeRequest(1L, RevokeReason.REQUEST_ENDED, OWNER_ID, NOW);

        assertThat(savedLogs()).isEmpty();
    }

    @Test
    void 요청_ID가_없으면_요청_단위로_회수하지_않는다() {
        assertThatThrownBy(() -> writer.revokeRequest(null, RevokeReason.REQUEST_ENDED, OWNER_ID, NOW))
                .isInstanceOf(NullPointerException.class);

        verify(permissionRepository, never()).findAllByRequestIdAndStatusIn(any(), any());
    }

    // === 항목 삭제 회수 (#38) ===

    @Test
    void 항목_삭제는_정보_주인이_수행자로_남는다() {
        // 공개 항목 제외와 같이 정보 주인이 직접 한 회수 (D 요구사항명세 F-10)
        RequestInformationPermission active = active(100L, 10L);

        writer.revokeByRequestEvent(List.of(active), RevokeReason.ITEM_DELETED, OWNER_ID, NOW);

        assertThat(savedLogs()).singleElement()
                .extracting(InformationAccessLog::getActorUserId).isEqualTo(OWNER_ID);
    }

    @Test
    void 항목_단위_회수는_그_항목의_열린_권한을_모두_ITEM_DELETED로_끝내고_ACTIVE였던_것만_돌려준다() {
        // 같은 항목(10번)이 요청 두 개에 공개됨: 하나는 수락 전(PENDING), 하나는 수락 후(ACTIVE)
        RequestInformationPermission pending = pending(100L, 10L);
        RequestInformationPermission active = active(101L, 10L);
        when(permissionRepository.findAllByInformationIdAndStatusIn(any(), any())).thenReturn(List.of(pending, active));

        List<RequestInformationPermission> wasActive = writer.revokeItem(10L, OWNER_ID, NOW);

        assertRevoked(pending, RevokeReason.ITEM_DELETED);
        assertRevoked(active, RevokeReason.ITEM_DELETED);
        assertThat(wasActive).containsExactly(active);                                                 // 알림 대상은 ACTIVE였던 것만
        assertThat(savedLogs()).extracting(InformationAccessLog::getPermissionId).containsExactly(101L); // 이력도 ACTIVE만
        verify(permissionRepository).findAllByInformationIdAndStatusIn(10L,
                List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE));
    }

    @Test
    void 항목_단위_회수할_권한이_없으면_빈_목록을_돌려준다() {
        // 공유한 적 없거나 모두 이미 회수된 항목
        when(permissionRepository.findAllByInformationIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThat(writer.revokeItem(10L, OWNER_ID, NOW)).isEmpty();
        assertThat(savedLogs()).isEmpty();
    }

    @Test
    void 항목_ID가_없으면_회수와_ID_정리를_하지_않는다() {
        assertThatThrownBy(() -> writer.revokeItem(null, OWNER_ID, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writer.clearInformationId(null)).isInstanceOf(NullPointerException.class);

        verify(permissionRepository, never()).findAllByInformationIdAndStatusIn(any(), any());
        verify(permissionRepository, never()).clearInformationId(any());
    }

    @Test
    void 항목_ID_정리는_저장소에_그대로_맡기고_바뀐_행_수를_돌려준다() {
        when(permissionRepository.clearInformationId(10L)).thenReturn(3);

        assertThat(writer.clearInformationId(10L)).isEqualTo(3);
    }

    @Test
    void 끝나지_않은_권한은_PENDING과_ACTIVE로_조회한다() {
        writer.findOpenPermissions(7L);

        verify(permissionRepository).findAllByAssignmentIdAndStatusIn(7L,
                List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE));
    }

    // === 도우미 ===

    private void assertInvalidSelection(List<Long> informationIds) {
        assertThatThrownBy(() -> writer.loadOwnedItemTypes(OWNER_ID, informationIds))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
    }

    private void assertRevoked(RequestInformationPermission permission, RevokeReason reason) {
        assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
        assertThat(permission.getRevokeReason()).isEqualTo(reason);
        assertThat(permission.getRevokedAt()).isEqualTo(NOW);
    }

    /** saveAll이 받은 목록을 그대로 돌려주게 함 (DB 없이 저장 결과 확인) */
    private void returnSavedPermissions() {
        when(permissionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<InformationAccessLog> savedLogs() {
        ArgumentCaptor<List<InformationAccessLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(accessLogRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static SafetyInformation item(Long id, Long ownerId, InformationType type) {
        SafetyInformation information = SafetyInformation.create(ownerId, type, "이름", "내용");
        ReflectionTestUtils.setField(information, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return information;
    }

    private static RequestInformationPermission pending(Long id, Long informationId) {
        RequestInformationPermission permission =
                RequestInformationPermission.createPending(1L, 7L, informationId, InformationType.ALLERGY);
        ReflectionTestUtils.setField(permission, "id", id);
        return permission;
    }

    private static RequestInformationPermission active(Long id, Long informationId) {
        RequestInformationPermission permission = pending(id, informationId);
        permission.activate(NOW.minusDays(1), null);
        return permission;
    }
}
