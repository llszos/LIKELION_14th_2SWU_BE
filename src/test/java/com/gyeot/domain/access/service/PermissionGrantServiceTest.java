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

        // 재확인 수락의 항목 거르기는 기본적으로 모두 통과 (거르는 경우는 해당 테스트에서 따로 지정)
        when(permissionWriter.filterOwnedItemIds(any(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
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
        List<RequestInformationPermission> previous = List.of(pending(500L, 100L));
        when(permissionWriter.lockOpenPermissionsById(7L)).thenReturn(previous);

        service.grantOnTargetChanged(new CareRequestTargetChangedEvent(
                1L, 10L, RequestType.GENERAL, 7L, 20L, 8L, 21L, List.of(100L, 102L), NOW));

        // 이전 지정은 권한 ID로 잠가 회수한 뒤 새 지정에 생성 (범위 잠금은 쓰지 않음, #38 리뷰 반영)
        InOrder order = inOrder(permissionWriter);
        order.verify(permissionWriter).lockOpenPermissionsById(7L);
        order.verify(permissionWriter).revokeByRequestEvent(previous, RevokeReason.ASSIGNMENT_CLOSED, 10L, NOW);
        order.verify(permissionWriter).createPending(1L, 8L, 10L, List.of(100L, 102L));
        verify(permissionWriter, never()).findOpenPermissions(any());
        verify(permissionWriter, never()).revokeAssignment(any(), any(), any(), any());
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
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(kept, removed));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(removed), RevokeReason.SHARING_EXCLUDED, 10L, NOW);
        verify(permissionWriter).createPending(1L, 7L, 10L, List.of(102L)); // 유지된 100은 다시 만들지 않음
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
        verifyNoInteractions(careRequestSharingReader); // 수락 전은 기한이 필요 없어 조회하지 않음
    }

    @Test
    void 수락_전_수정은_유지_항목까지_전체_목록을_확인하고_거르지_않는다() {
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(pending(500L, 100L)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).loadOwnedItemTypes(10L, List.of(100L, 102L)); // 문제가 있으면 여기서 전체 거부
        verify(permissionWriter, never()).filterOwnedItemIds(any(), anyList());
    }

    @Test
    void 재확인_수락은_대기_중_삭제된_항목을_빼고_남은_항목으로_반영한다() {
        // 지금 공개 중: 100 / 제안: 100, 102, 103 → 대기 중 103 삭제 → 102만 추가
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(active(500L, 100L, null)));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L, 102L, 103L))).thenReturn(List.of(100L, 102L));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L, 103L), NOW));

        verify(permissionWriter, never()).loadOwnedItemTypes(any(), anyList()); // 전체 거부 확인은 하지 않음
        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, null);
    }

    @Test
    void 재확인_수락에서_유지_항목이_삭제됐으면_그_항목만_회수된다() {
        // 지금 공개 중: 100, 101 / 제안: 100, 101 그대로인데 대기 중 101 삭제 → 101은 반영 목록에서 빠져 회수
        RequestInformationPermission kept = active(500L, 100L, null);
        RequestInformationPermission deleted = active(501L, 101L, null);
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(kept, deleted));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L, 101L))).thenReturn(List.of(100L));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 101L), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(deleted), RevokeReason.SHARING_EXCLUDED, 10L, NOW);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void 급한_요청의_재확인_수락은_추가_항목을_최초_수락_시각_기준_기한으로_만든다() {
        // 수락은 3시간 전, 수정 확정은 지금 → 기한은 수락 + 24시간 (지금 + 24시간이 아님)
        LocalDateTime acceptedAt = NOW.minusHours(3);
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(active(500L, 100L, acceptedAt.plusHours(24))));
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
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of());
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.URGENT, acceptedAt)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(102L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, acceptedAt.plusHours(24));
    }

    @Test
    void 일반_요청의_재확인_수락은_기한_없이_ACTIVE로_만든다() {
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(active(500L, 100L, null)));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, null);
    }

    @Test
    void 빈_목록이면_공개_중인_항목을_모두_회수하고_만들지_않는다() {
        RequestInformationPermission first = active(500L, 100L, null);
        RequestInformationPermission second = active(501L, 101L, null);
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(first, second));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(first, second), RevokeReason.SHARING_EXCLUDED, 10L, NOW);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
        verify(permissionWriter, never()).createPending(any(), any(), any(), anyList());
    }

    @Test
    void 바뀐_것이_없으면_회수할_목록이_비어_있고_만들지_않는다() {
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(pending(500L, 100L)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L), NOW));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestInformationPermission>> excluded = ArgumentCaptor.forClass(List.class);
        verify(permissionWriter).revokeByRequestEvent(excluded.capture(), eq(RevokeReason.SHARING_EXCLUDED), eq(10L), eq(NOW));
        assertThat(excluded.getValue()).isEmpty();
        verify(permissionWriter, never()).createPending(any(), any(), any(), anyList());
    }

    // === 재확인 수락과 직접 철회 (#46) ===

    @Test
    void 재확인_수락은_최신_권한이_직접_철회인_항목을_다시_열지_않고_새_항목만_ACTIVE로_만든다() {
        // 100은 정보 주인이 직접 철회한 상태 / 제안: 100, 102 → 102만 추가 (100은 닫힌 채)
        when(permissionWriter.lockChangePermissionsById(eq(7L), any()))
                .thenReturn(List.of(revoked(500L, 100L, RevokeReason.OWNER_REVOKED)));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L, 102L))).thenReturn(List.of(100L, 102L));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).lockChangePermissionsById(7L, List.of(100L, 102L)); // 반영 목록 항목의 REVOKED까지 한 번에 잠금
        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, null);
    }

    @Test
    void 직접_철회_뒤_다시_허용된_항목은_최신_권한이_ACTIVE라_유지로_본다() {
        // 100: 직접 철회(500) → 다시 허용(501, ACTIVE) / 제안: 100, 102 → 100은 그대로, 102만 추가
        RequestInformationPermission reallowed = active(501L, 100L, null);
        when(permissionWriter.lockChangePermissionsById(eq(7L), any()))
                .thenReturn(List.of(revoked(500L, 100L, RevokeReason.OWNER_REVOKED), reallowed));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L, 102L))).thenReturn(List.of(100L, 102L));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).revokeByRequestEvent(List.of(), RevokeReason.SHARING_EXCLUDED, 10L, NOW); // 빠진 항목 없음
        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(102L), NOW, null);
    }

    @Test
    void 직접_철회가_아닌_사유로_회수된_항목은_재확인_수락으로_다시_추가된다() {
        // 100은 전에 요청 수정으로 빠진 항목(SHARING_EXCLUDED) / 제안: 100 → 요청자가 다시 고른 것이라 ACTIVE
        when(permissionWriter.lockChangePermissionsById(eq(7L), any()))
                .thenReturn(List.of(revoked(500L, 100L, RevokeReason.SHARING_EXCLUDED)));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L))).thenReturn(List.of(100L));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(100L), NOW, null);
    }

    @Test
    void 과거에_직접_철회됐어도_최신_권한이_다른_사유면_재확인_수락으로_다시_추가된다() {
        // 100: 직접 철회(500) → 다시 허용으로 새 권한(501) → 요청 수정에서 빠져 501이 SHARING_EXCLUDED / 제안: 100
        // 최신 권한(501) 기준이라 과거 직접 철회 기록(500)은 적용하지 않음 (요청자가 다시 고른 것)
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(
                revoked(500L, 100L, RevokeReason.OWNER_REVOKED),
                revoked(501L, 100L, RevokeReason.SHARING_EXCLUDED)));
        when(permissionWriter.filterOwnedItemIds(10L, List.of(100L))).thenReturn(List.of(100L));
        when(careRequestSharingReader.findById(1L)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL, NOW.minusHours(3))));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, 30L, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L), NOW));

        verify(permissionWriter).createActive(1L, 7L, 10L, 20L, List.of(100L), NOW, null);
    }

    @Test
    void 수락_전_수정은_REVOKED_권한을_잠그지_않는다() {
        // 수락 전에는 직접 철회가 있을 수 없어 열린 권한만 잠금 (항목 목록 null)
        when(permissionWriter.lockChangePermissionsById(eq(7L), any())).thenReturn(List.of(pending(500L, 100L)));

        service.grantOnChangeConfirmed(new CareRequestChangeConfirmedEvent(
                1L, 10L, null, 7L, 20L, Set.of(ChangeableField.INFORMATION_IDS), List.of(100L, 102L), NOW));

        verify(permissionWriter).lockChangePermissionsById(7L, null);
        verify(permissionWriter).createPending(1L, 7L, 10L, List.of(102L));
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

    private static RequestInformationPermission revoked(Long id, Long informationId, RevokeReason reason) {
        RequestInformationPermission permission = active(id, informationId, null);
        permission.revoke(reason, NOW.minusMinutes(30));
        return permission;
    }
}
