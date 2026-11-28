package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.service.PermissionUserWithdrawalService;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** A의 회원 탈퇴 이벤트에 맞춰 정보 주인의 D 데이터를 같은 트랜잭션에서 정리합니다. */
@Component
@RequiredArgsConstructor
public class UserWithdrawalPermissionListener {

    private final PermissionUserWithdrawalService withdrawalService;

    /** 처리 순서: B(100) → C(200) → D(300). C 요청 정리가 끝난 뒤 D 원본을 정리함 (#159) */
    @EventListener
    @Order(300)
    public void onUserWithdrawn(UserWithdrawnEvent event) {
        withdrawalService.cleanupOwnerData(event);
    }
}
