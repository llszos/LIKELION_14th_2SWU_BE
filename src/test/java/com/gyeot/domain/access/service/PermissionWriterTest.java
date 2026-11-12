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

/** DB 없이 Repository를 Mock으로 바꿔 권한 공통 처리 규칙만 확인합니다. (#42) */
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

        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.OWNER_REVOKED); // 처음 끝난 이유·시각 유지
        assertThat(revoked.getRevokedAt()).isEqualTo(earlier);
        assertThat(savedLogs()).isEmpty();
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
