package com.gyeot.domain.access.dto.response;

import java.util.List;

/**
 * 공유 다시 허용 응답입니다. (노션 D 파트 API 명세 '공유 다시 허용')
 *
 * - 다시 허용 뒤 카드 상태(SHARING)와 다시 공개된 항목을 내려줍니다.
 * - 철회 후 삭제한 항목은 다시 열리지 않아 sharedItems에 없습니다.
 */
public record SharingReallowResponse(
        Long assignmentId,                // 공유 카드 ID
        SharingStatus status,             // 카드 상태 (SHARING)
        List<SharedItemName> sharedItems  // 다시 공개된 항목 (작성순)
) {

    public SharingReallowResponse {
        sharedItems = List.copyOf(sharedItems); // 만든 뒤 목록이 바뀌지 않게 복사
    }

    /** 다시 허용된 카드 응답 */
    public static SharingReallowResponse reallowed(Long assignmentId, List<SharedItemName> sharedItems) {
        return new SharingReallowResponse(assignmentId, SharingStatus.SHARING, sharedItems);
    }
}
