package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.service.PermissionGrantService;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
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
 */
@Component
@RequiredArgsConstructor
public class CareRequestPermissionListener {

    private final PermissionGrantService permissionGrantService;

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
}
