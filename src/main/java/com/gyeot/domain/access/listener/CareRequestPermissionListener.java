package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.service.PermissionGrantService;
import com.gyeot.domain.access.service.PermissionRevokeService;
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.event.CareRequestExpiredEvent;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * C 요청 이벤트를 받아 D 권한 처리로 넘깁니다. (이벤트 처리 영역 표: C → D)
 *
 * - @EventListener(동기): 이벤트를 발행한 C 트랜잭션 안에서 바로 실행됩니다. (이벤트 처리 규칙 3)
 * - 예외를 잡지 않습니다. D 처리가 실패하면 C 변경까지 함께 롤백됩니다.
 * - 판단과 저장은 서비스에 두고, 리스너는 이벤트별로 알맞은 메서드만 호출합니다.
 *
 * - 권한을 열거나 바꾸는 이벤트는 PermissionGrantService(#42), 끝내는 이벤트는 PermissionRevokeService(#43)로 넘깁니다.
 *   대상 변경은 이전 지정 회수와 새 지정 생성이 한 묶음이라 부여 쪽에서 함께 처리합니다.
 */
@Component
@RequiredArgsConstructor
public class CareRequestPermissionListener {

    private final PermissionGrantService permissionGrantService;   // 권한 생성, 활성화, 수정 반영 (#42)
    private final PermissionRevokeService permissionRevokeService; // 요청 종료, 지정 마감 시 회수 (#43)

    /** 요청 생성 → 공유 예정(PENDING) 권한 생성 */
    @EventListener
    public void onRequestCreated(CareRequestCreatedEvent event) {
        permissionGrantService.grantOnRequestCreated(event);
    }

    /** 수락 → 공유 예정 권한 활성화 + GRANTED 이력 */
    @EventListener
    public void onAccepted(CareRequestAcceptedEvent event) {
        permissionGrantService.grantOnAccepted(event);
    }

    /** 대상 변경(다시 부탁하기) → 이전 지정 권한 회수 + 새 지정 공유 예정 권한 생성 */
    @EventListener
    public void onTargetChanged(CareRequestTargetChangedEvent event) {
        permissionGrantService.grantOnTargetChanged(event);
    }

    /** 수정 확정 → 공개 항목 추가와 제외 반영 */
    @EventListener
    public void onChangeConfirmed(CareRequestChangeConfirmedEvent event) {
        permissionGrantService.grantOnChangeConfirmed(event);
    }

    /** 요청 완료(종료 시간 도달 포함) → 요청의 모든 권한 회수 (REQUEST_ENDED) */
    @EventListener
    public void onCompleted(CareRequestCompletedEvent event) {
        permissionRevokeService.revokeOnCompleted(event);
    }

    /** 요청 취소(재확인 거절, 상황 종료 포함) → 요청의 모든 권한 회수 (REQUEST_ENDED) */
    @EventListener
    public void onCanceled(CareRequestCanceledEvent event) {
        permissionRevokeService.revokeOnCanceled(event);
    }

    /** 요청 만료 → 요청의 모든 권한 회수 (REQUEST_ENDED) */
    @EventListener
    public void onExpired(CareRequestExpiredEvent event) {
        permissionRevokeService.revokeOnExpired(event);
    }

    /** 수락 철회 → 그 지정의 권한만 회수 (ASSIGNMENT_CLOSED) */
    @EventListener
    public void onAcceptanceWithdrawn(CareRequestAcceptanceWithdrawnEvent event) {
        permissionRevokeService.revokeOnAcceptanceWithdrawn(event);
    }

    /** 지정 마감(관계 해제, 상황 나가기, 대상자 탈퇴) → 그 지정의 권한만 회수 (ASSIGNMENT_CLOSED) */
    @EventListener
    public void onAssignmentClosed(CareRequestAssignmentClosedEvent event) {
        permissionRevokeService.revokeOnAssignmentClosed(event);
    }
}
