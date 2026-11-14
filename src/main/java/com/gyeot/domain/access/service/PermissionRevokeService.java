package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestExpiredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 요청 이벤트에 따라 안심카드 접근 권한을 회수합니다. (F-09, F-10, ERD 4.11, 8.4, 9.1, #43)
 *
 * - 회수 사유는 끝나는 범위로 정합니다.
 *   요청 자체가 끝나면(완료, 취소, 만료) REQUEST_ENDED로 그 요청의 모든 열린 권한을 끝내고,
 *   지정 하나만 끝나면(수락 철회, 지정 마감) ASSIGNMENT_CLOSED로 그 지정의 열린 권한만 끝냅니다.
 * - 취소 사유(cancelReason)나 마감 사유(closeReason)가 회원 탈퇴여도 위 규칙을 따릅니다.
 *   탈퇴 전용 사유(USER_WITHDRAWN)는 회원 탈퇴 처리(#49, SHOULD 1차)에서 다룹니다.
 *
 * - 이벤트를 발행한 C 트랜잭션 안에서만 실행합니다. (MANDATORY)
 *   C는 요청 행을 잠근 트랜잭션 안에서 동기로 발행하므로, 같은 요청의 부여와 회수가 순서대로 처리됩니다.
 * - 이력, 수행자, 이미 회수된 권한 건너뛰기 규칙은 PermissionWriter.revokeByRequestEvent와 같습니다.
 *   ACTIVE였던 권한만 AUTO_REVOKED 이력을 남기고, 수행자는 시스템 처리라 NULL입니다.
 * - C 이벤트로 회수된 경우는 C가 알림을 보내므로 PermissionRevokedEvent를 발행하지 않습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionRevokeService {

    private final PermissionWriter permissionWriter;

    /**
     * 요청 완료: 그 요청의 모든 열린 권한을 REQUEST_ENDED로 끝냅니다.
     *
     * 종료 시간 도달로 자동 완료되는 경우도 같은 이벤트로 옵니다.
     */
    public void revokeOnCompleted(CareRequestCompletedEvent event) {

        // 정보 주인 = 요청자, 회수 시각 = 완료 시각
        permissionWriter.revokeRequest(event.requestId(), RevokeReason.REQUEST_ENDED,
                event.requesterId(), event.occurredAt());
    }

    /**
     * 요청 취소: 그 요청의 모든 열린 권한을 REQUEST_ENDED로 끝냅니다.
     *
     * - 요청자 직접 취소, 상황 종료, 재확인 거절, 진행 중 담당자 이탈, 관계 해제 모두 요청이 끝난 것이라 같은 사유입니다.
     * - 열린 지정이 없으면 assignmentId가 NULL이라, 지정이 아닌 요청 기준으로 회수합니다.
     */
    public void revokeOnCanceled(CareRequestCanceledEvent event) {

        // assignmentId는 쓰지 않음 (NULL일 수 있고, 요청이 끝나면 모든 지정의 권한을 끝내야 함)
        permissionWriter.revokeRequest(event.requestId(), RevokeReason.REQUEST_ENDED,
                event.requesterId(), event.occurredAt());
    }

    /**
     * 요청 만료: 그 요청의 모든 열린 권한을 REQUEST_ENDED로 끝냅니다.
     *
     * 아무도 수락하지 않고 만료되면 권한은 모두 PENDING이라, 상태만 바뀌고 이력은 남지 않습니다.
     */
    public void revokeOnExpired(CareRequestExpiredEvent event) {

        // 취소와 같은 이유로 assignmentId는 쓰지 않음
        permissionWriter.revokeRequest(event.requestId(), RevokeReason.REQUEST_ENDED,
                event.requesterId(), event.occurredAt());
    }

    /**
     * 수락 철회: 철회한 담당자의 지정 권한만 ASSIGNMENT_CLOSED로 끝냅니다.
     *
     * 요청은 계속 열려 있어 다시 부탁하기로 다른 대상을 지정할 수 있으므로, 요청 전체가 아닌 그 지정만 회수합니다.
     */
    public void revokeOnAcceptanceWithdrawn(CareRequestAcceptanceWithdrawnEvent event) {

        // 정보 주인 = 요청자 (철회한 사람은 담당자지만 권한의 주인은 요청자)
        permissionWriter.revokeAssignment(event.assignmentId(), RevokeReason.ASSIGNMENT_CLOSED,
                event.requesterId(), event.occurredAt());
    }

    /**
     * 지정 마감: 마감된 지정의 권한만 ASSIGNMENT_CLOSED로 끝냅니다.
     *
     * - 관계 해제, 상황 나가기, 대상자 회원 탈퇴로 그 대상의 지정만 끝난 경우입니다.
     * - 대상 변경, 수락 철회, 요청 종료로 인한 마감은 C가 이 이벤트로 보내지 않고 각자의 이벤트로 보냅니다.
     *   (CareRequestAssignmentClosedEvent가 생성 시 거부)
     */
    public void revokeOnAssignmentClosed(CareRequestAssignmentClosedEvent event) {

        // closeReason은 회수 사유를 나누는 데 쓰지 않음 (모두 지정 하나만 끝난 경우)
        permissionWriter.revokeAssignment(event.assignmentId(), RevokeReason.ASSIGNMENT_CLOSED,
                event.requesterId(), event.occurredAt());
    }
}
