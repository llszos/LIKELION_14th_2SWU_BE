package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 회수 이벤트마다 회수 범위(요청 전체, 지정 하나)와 사유가 공통 처리(PermissionWriter)에 올바르게 넘어가는지 확인합니다. (#43)
 *
 * 실제 권한 상태 변경과 이력은 PermissionWriterTest와 통합 테스트에서 확인합니다.
 */
class PermissionRevokeServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 15, 0);

    // 모든 테스트에서 같은 값: 요청 1번, 요청자(정보 주인) 10번, 지정 7번, 대상자 20번
    private static final Long REQUEST_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long ASSIGNMENT_ID = 7L;
    private static final Long TARGET_ID = 20L;

    private PermissionWriter permissionWriter;
    private PermissionRevokeService service;

    @BeforeEach
    void setUp() {
        permissionWriter = mock(PermissionWriter.class);
        service = new PermissionRevokeService(permissionWriter);
    }

    // === 요청 자체가 끝남: 요청 전체 회수, REQUEST_ENDED ===

    @Test
    void 요청_완료면_요청의_모든_권한을_REQUEST_ENDED로_회수한다() {

        // 완료 처리한 사람은 담당자(20번)여도 회수 이력의 정보 주인은 요청자
        service.revokeOnCompleted(new CareRequestCompletedEvent(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, TARGET_ID, TARGET_ID, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
        verify(permissionWriter, never()).revokeAssignment(any(), any(), any(), any()); // 지정 단위로는 회수하지 않음
    }

    @Test
    void 요청_취소면_요청의_모든_권한을_REQUEST_ENDED로_회수한다() {

        service.revokeOnCanceled(new CareRequestCanceledEvent(
                REQUEST_ID, REQUESTER_ID, CancelReason.REQUESTER_CANCELED, ASSIGNMENT_ID, TARGET_ID, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
        verify(permissionWriter, never()).revokeAssignment(any(), any(), any(), any());
    }

    @Test
    void 열린_지정_없이_취소돼도_요청_기준으로_회수한다() {

        // 열린 지정이 없으면 C가 assignmentId, targetUserId를 NULL로 보냄
        service.revokeOnCanceled(new CareRequestCanceledEvent(
                REQUEST_ID, REQUESTER_ID, CancelReason.SITUATION_ENDED, null, null, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
    }

    @Test
    void 관계_해제로_요청이_취소돼도_요청이_끝난_것이라_REQUEST_ENDED로_회수한다() {

        // 진행 중 관계 해제는 C가 요청 자체를 취소함 (지정만 끝나는 관계 해제는 지정 마감 이벤트로 옴)
        service.revokeOnCanceled(new CareRequestCanceledEvent(
                REQUEST_ID, REQUESTER_ID, CancelReason.RELATIONSHIP_ENDED, ASSIGNMENT_ID, TARGET_ID, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
    }

    @Test
    void 요청_만료면_요청의_모든_권한을_REQUEST_ENDED로_회수한다() {

        service.revokeOnExpired(new CareRequestExpiredEvent(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, TARGET_ID, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
        verify(permissionWriter, never()).revokeAssignment(any(), any(), any(), any());
    }

    @Test
    void 열린_지정_없이_만료돼도_요청_기준으로_회수한다() {

        service.revokeOnExpired(new CareRequestExpiredEvent(REQUEST_ID, REQUESTER_ID, null, null, NOW));

        verify(permissionWriter).revokeRequest(REQUEST_ID, RevokeReason.REQUEST_ENDED, REQUESTER_ID, NOW);
    }

    // === 지정 하나만 끝남: 그 지정만 회수, ASSIGNMENT_CLOSED ===

    @Test
    void 수락_철회면_그_지정의_권한만_ASSIGNMENT_CLOSED로_회수한다() {

        service.revokeOnAcceptanceWithdrawn(new CareRequestAcceptanceWithdrawnEvent(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, TARGET_ID, NOW));

        verify(permissionWriter).revokeAssignment(ASSIGNMENT_ID, RevokeReason.ASSIGNMENT_CLOSED, REQUESTER_ID, NOW);
        verify(permissionWriter, never()).revokeRequest(any(), any(), any(), any()); // 요청은 계속 열려 있음
    }

    @Test
    void 지정_마감이면_마감_사유와_상관없이_그_지정의_권한만_ASSIGNMENT_CLOSED로_회수한다() {

        // C가 이 이벤트로 보내는 마감 사유: 관계 해제, 상황 나가기, 대상자 회원 탈퇴
        for (AssignmentCloseReason closeReason : new AssignmentCloseReason[]{
                AssignmentCloseReason.RELATIONSHIP_ENDED,
                AssignmentCloseReason.PARTICIPANT_LEFT,
                AssignmentCloseReason.USER_WITHDRAWN}) {

            service.revokeOnAssignmentClosed(new CareRequestAssignmentClosedEvent(
                    REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, TARGET_ID, closeReason, NOW));
        }

        // 세 사유 모두 같은 호출 (회원 탈퇴 전용 사유는 #49 범위)
        verify(permissionWriter, times(3))
                .revokeAssignment(ASSIGNMENT_ID, RevokeReason.ASSIGNMENT_CLOSED, REQUESTER_ID, NOW);
        verify(permissionWriter, never()).revokeRequest(any(), any(), any(), any());
    }
}
