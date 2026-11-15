package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.event.PermissionRevokedEvent;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 항목 삭제 시 권한 정리 순서와 담당자 알림 이벤트(PermissionRevokedEvent) 구성을 확인합니다. (#38)
 *
 * 실제 회수와 information_id 정리는 PermissionWriterTest와 통합 테스트에서 확인하고, 여기서는 PermissionWriter를 Mock으로 둡니다.
 */
class PermissionItemDeletionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 15, 0);
    private static final Long OWNER_ID = 10L;       // 정보 주인 (항목 주인, 요청자)
    private static final Long INFORMATION_ID = 100L; // 삭제할 항목

    private PermissionWriter permissionWriter;
    private CareRequestSharingReader careRequestSharingReader;
    private ApplicationEventPublisher eventPublisher;
    private PermissionItemDeletionService service;

    @BeforeEach
    void setUp() {
        permissionWriter = mock(PermissionWriter.class);
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new PermissionItemDeletionService(permissionWriter, careRequestSharingReader, eventPublisher);
    }

    @Test
    void 회수하고_알림을_보낸_뒤_항목_ID를_정리한다() {
        // 요청 1번(지정 7번, 담당자 20번)에서 ACTIVE였던 권한
        when(permissionWriter.revokeItem(INFORMATION_ID, OWNER_ID, NOW)).thenReturn(List.of(permission(1L, 7L)));
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(1L, request(1L, 7L, 20L)));

        service.revokeOnItemDeleted(OWNER_ID, INFORMATION_ID, NOW);

        // 회수 → 알림 → ID 정리 순서 (정리 전에 회수해야 열린 권한을 항목 ID로 찾을 수 있음)
        InOrder order = inOrder(permissionWriter, eventPublisher);
        order.verify(permissionWriter).revokeItem(INFORMATION_ID, OWNER_ID, NOW);
        order.verify(eventPublisher).publishEvent(new PermissionRevokedEvent(1L, 7L, OWNER_ID, 20L, RevokeReason.ITEM_DELETED));
        order.verify(permissionWriter).clearInformationId(INFORMATION_ID);
    }

    @Test
    void 요청과_담당자_묶음마다_이벤트를_한_건씩_발행한다() {
        // 같은 항목이 요청 두 개에 공개돼 둘 다 ACTIVE였음
        when(permissionWriter.revokeItem(INFORMATION_ID, OWNER_ID, NOW))
                .thenReturn(List.of(permission(1L, 7L), permission(2L, 9L)));
        when(careRequestSharingReader.findAllByIds(anyCollection()))
                .thenReturn(Map.of(1L, request(1L, 7L, 20L), 2L, request(2L, 9L, 21L)));

        service.revokeOnItemDeleted(OWNER_ID, INFORMATION_ID, NOW);

        assertThat(publishedEvents()).containsExactly(
                new PermissionRevokedEvent(1L, 7L, OWNER_ID, 20L, RevokeReason.ITEM_DELETED),
                new PermissionRevokedEvent(2L, 9L, OWNER_ID, 21L, RevokeReason.ITEM_DELETED));
    }

    @Test
    void 같은_지정의_권한이_여러_개여도_이벤트는_한_건이다() {
        // 묶음당 1건 계약 확인용 (지정 7번 권한 두 개)
        when(permissionWriter.revokeItem(INFORMATION_ID, OWNER_ID, NOW))
                .thenReturn(List.of(permission(1L, 7L), permission(1L, 7L)));
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(1L, request(1L, 7L, 20L)));

        service.revokeOnItemDeleted(OWNER_ID, INFORMATION_ID, NOW);

        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void ACTIVE였던_권한이_없으면_C_조회와_알림_없이_ID만_정리한다() {
        // 공유한 적 없거나 모두 수락 전(PENDING)이었음
        when(permissionWriter.revokeItem(INFORMATION_ID, OWNER_ID, NOW)).thenReturn(List.of());

        service.revokeOnItemDeleted(OWNER_ID, INFORMATION_ID, NOW);

        verifyNoInteractions(careRequestSharingReader, eventPublisher);
        verify(permissionWriter).clearInformationId(INFORMATION_ID); // 과거 권한이 가리키던 ID는 그래도 정리
    }

    @Test
    void C의_현재_지정과_다르거나_요청이_없으면_알림만_건너뛰고_정리는_계속한다() {
        // 지정 7번은 C에서 이미 다른 지정(8번)으로 바뀜, 요청 2번은 C에 없음
        when(permissionWriter.revokeItem(INFORMATION_ID, OWNER_ID, NOW))
                .thenReturn(List.of(permission(1L, 7L), permission(2L, 9L)));
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(1L, request(1L, 8L, 21L)));

        service.revokeOnItemDeleted(OWNER_ID, INFORMATION_ID, NOW);

        verify(eventPublisher, never()).publishEvent(any(Object.class)); // 담당자를 확정할 수 없어 알리지 않음
        verify(permissionWriter).clearInformationId(INFORMATION_ID);    // 회수와 정리는 그대로
    }

    // === 도우미 ===

    private List<Object> publishedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // 회수 후 상태의 권한 (요청 ID, 지정 ID만 쓰임)
    private static RequestInformationPermission permission(Long requestId, Long assignmentId) {
        RequestInformationPermission permission =
                RequestInformationPermission.createPending(requestId, assignmentId, INFORMATION_ID, InformationType.ALLERGY);
        permission.revoke(RevokeReason.ITEM_DELETED, NOW);
        return permission;
    }

    // C의 공유용 요청 정보 (현재 지정 ID와 담당자 ID만 쓰임)
    private static SharingRequestInfo request(Long requestId, Long assignmentId, Long assigneeId) {
        return new SharingRequestInfo(requestId, OWNER_ID, assignmentId, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                null, null, null, assigneeId, NOW.minusHours(1), HelpType.HOSPITAL);
    }
}
