package com.gyeot.domain.access.event;

import com.gyeot.domain.access.entity.RevokeReason;

/**
 * D가 단독으로 접근 권한을 끝냈을 때 발행하는 이벤트입니다. (이벤트 처리 영역 표: 권한 종료 D → B)
 * 받는 영역(B)은 이 이벤트로 담당자에게 공유 종료 알림을 저장합니다.
 * 직접 철회(OWNER_REVOKED), 안심카드 항목 삭제(ITEM_DELETED), 급한 요청 24시간 만료(ACCESS_EXPIRED)일 때만 발행합니다.
 * C의 요청 이벤트로 권한이 끝난 경우는 C의 이벤트로 알림이 가므로 발행하지 않습니다.
 * 항목마다가 아니라 (요청, 담당자) 묶음당 1건 발행합니다. (예: 항목 3개를 철회해도 1건)
 * 공유 1건은 C-06 공유 관리의 카드 하나와 같습니다.
 * ACTIVE였던 권한이 끝났을 때만 발행합니다. 수락 전(PENDING) 권한은 담당자가 정보를 본 적이 없어 알리지 않습니다.
 *
 * @param requestId       권한이 끝난 요청 ID (알림을 눌렀을 때 이동할 요청)
 * @param assignmentId    권한이 끝난 담당 지정 ID
 * @param ownerUserId     정보 주인 ID (누구의 정보였는지)
 * @param assigneeUserId  알림을 받을 담당자 ID
 * @param revokeReason    권한이 끝난 이유 (OWNER_REVOKED, ITEM_DELETED, ACCESS_EXPIRED 중 하나)
 */

public record PermissionRevokedEvent(
        Long requestId,
        Long assignmentId,
        Long ownerUserId,
        Long assigneeUserId,
        RevokeReason revokeReason
) {
}
