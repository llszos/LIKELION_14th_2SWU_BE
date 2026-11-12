package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 이벤트 값이 공통 처리(PermissionWriter)에 올바르게 넘어가는지 확인합니다. (#42) */
class PermissionGrantServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 0);

    private PermissionWriter permissionWriter;
    private CareRequestSharingReader careRequestSharingReader;
    private PermissionGrantService service;

    @BeforeEach
    void setUp() {
        permissionWriter = mock(PermissionWriter.class);
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        service = new PermissionGrantService(permissionWriter, careRequestSharingReader);
    }

    @Test
    void 요청_생성이면_요청자를_정보_주인으로_최초_지정에_PENDING을_만든다() {
        // 요청 1번, 요청자 10번, 지정 7번, 대상자 20번, 공개 항목 100, 101번
        service.grantOnRequestCreated(new CareRequestCreatedEvent(
                1L, 10L, RequestType.GENERAL, 7L, 20L, List.of(100L, 101L), NOW));

        verify(permissionWriter).createPending(1L, 7L, 10L, List.of(100L, 101L));
    }

    @Test
    void 일반_요청_수락이면_기한_없이_활성화하고_담당자를_수행자로_남긴다() {
        service.grantOnAccepted(new CareRequestAcceptedEvent(1L, 10L, RequestType.GENERAL, 7L, 20L, NOW));

        verify(permissionWriter).activatePending(7L, 10L, 20L, NOW, null);
    }

    @Test
    void 급한_요청_수락이면_수락_시각부터_24시간_기한으로_활성화한다() {
        service.grantOnAccepted(new CareRequestAcceptedEvent(1L, 10L, RequestType.URGENT, 7L, 20L, NOW));

        verify(permissionWriter).activatePending(7L, 10L, 20L, NOW, NOW.plusHours(24));
    }

    @Test
    void 대상_변경이면_이전_지정을_먼저_회수하고_새_지정에_이벤트_항목으로_PENDING을_만든다() {
        // 요청 1번, 요청자 10번, 이전 지정 7번(대상 20번) → 새 지정 8번(대상 21번), 이번에 고른 항목 100, 102번
        service.grantOnTargetChanged(new CareRequestTargetChangedEvent(
                1L, 10L, RequestType.GENERAL, 7L, 20L, 8L, 21L, List.of(100L, 102L), NOW));

        InOrder order = inOrder(permissionWriter);
        order.verify(permissionWriter).revokeAssignment(7L, RevokeReason.ASSIGNMENT_CLOSED, 10L, NOW);
        order.verify(permissionWriter).createPending(1L, 8L, 10L, List.of(100L, 102L));
    }

    // === 수정 확정 ===

    @Test
    void 공개_항목을_바꾸지_않은_수정은_아무것도_하지_않는다() {
        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.PLACE), null, NOW));

        verifyNoInteractions(permissionWriter);
    }

    @Test
    void 열린_지정이_없으면_아무것도_하지_않는다() {
        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, null, null, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L), NOW));

        verifyNoInteractions(permissionWriter);
    }

    @Test
    void 수락_전_수정은_빠진_항목을_회수하고_추가된_항목만_PENDING으로_만든다() {
        // 지금 공개 중: 100(유지), 101(제외) → 변경 후: 100, 102(추가)
        RequestInformationPermission kept = pending(500L, 100L);
        RequestInformationPermission removed = pending(501L, 101L);
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of(kept, removed));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(removed), RevokeReason.SHARING_EXCLUDED, 10L, NOW);
        verify(permissionWriter).createPending(1L, 7L, 10L, List.of(102L)); // 유지된 100은 다시 만들지 않음
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
        verifyNoInteractions(careRequestSharingReader); // 수락 전은 기한이 필요 없어 조회하지 않음
    }

    @Test
    void 급한_요청의_재확인_수락은_추가_항목을_최초_수락_시각_기준_기한으로_만든다() {
        // 수락은 3시간 전, 수정 확정은 지금 → 기한은 수락 + 24시간 (지금 + 24시간이 아님)
        LocalDateTime acceptedAt = NOW.minusHours(3);
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of(active(500L, 100L, acceptedAt.plusHours(24))));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.URGENT, acceptedAt)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, acceptedAt.plusHours(24));
        verify(permissionWriter, never()).createPending(any(), any(), any(), anyList());
    }

    @Test
    void 급한_요청은_기존_권한이_없어도_수락_시각으로_기한을_계산한다() {
        // 공개 0개로 보낸 뒤 수락 후 처음 추가하는 경우
        LocalDateTime acceptedAt = NOW.minusHours(3);
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of());
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.URGENT, acceptedAt)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(102L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, acceptedAt.plusHours(24));
    }

    @Test
    void 일반_요청의_재확인_수락은_기한_없이_ACTIVE로_만든다() {
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of(active(500L, 100L, null)));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, null);
    }

    @Test
    void 빈_목록이면_공개_중인_항목을_모두_회수하고_만들지_않는다() {
        RequestInformationPermission first = active(500L, 100L, null);
        RequestInformationPermission second = active(501L, 101L, null);
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of(first, second));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(first, second), RevokeReason.SHARING_EXCLUDED, 10L, NOW);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
        verify(permissionWriter, never()).createPending(any(), any(), any(), anyList());
    }

    @Test
    void 바뀐_것이_없으면_회수할_목록이_비어_있고_만들지_않는다() {
        when(permissionWriter.findOpenPermissions(7L)).thenReturn(List.of(pending(500L, 100L)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L), NOW));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestInformationPermission>> excluded = ArgumentCaptor.forClass(List.class);
        verify(permissionWriter).revokeByRequestEvent(excluded.capture(), eq(RevokeReason.SHARING_EXCLUDED), eq(10L), eq(NOW));
        assertThat(excluded.getValue()).isEmpty();
        verify(permissionWriter, never()).createPending(any(), any(), any(), anyList());
    }

    // === 도우미 ===

    /** C 공유용 요청 조회 결과 (요청 1번, 요청자 10번, 현재 담당 지정 7번, 담당자 20번) */
    private static SharingRequestInfo requestInfo(RequestType requestType, LocalDateTime acceptedAt) {
        return new SharingRequestInfo(1L, 10L, 7L, CareRequestStatus.ACCEPTED, requestType,
                null, null, null, 20L, acceptedAt, HelpType.HOSPITAL);
    }

    private static RequestInformationPermission pending(Long id, Long informationId) {
        RequestInformationPermission permission =
                RequestInformationPermission.createPending(1L, 7L, informationId, InformationType.ALLERGY);
        ReflectionTestUtils.setField(permission, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return permission;
    }

    private static RequestInformationPermission active(Long id, Long informationId, LocalDateTime expiresAt) {
        RequestInformationPermission permission = pending(id, informationId);
        permission.activate(NOW.minusHours(1), expiresAt);
        return permission;
    }
}
