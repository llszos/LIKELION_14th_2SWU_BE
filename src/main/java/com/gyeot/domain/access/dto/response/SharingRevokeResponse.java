package com.gyeot.domain.access.dto.response;

/**
 * 공유 직접 철회 응답입니다. (노션 D 파트 API 명세 '공유 직접 철회')
 *
 * - 철회 뒤 카드 상태를 내려줍니다. 성공하면 status는 항상 REVOKED입니다.
 * - 이미 철회된 카드를 다시 철회해도 같은 응답입니다. (변경 없이 200)
 * - FE는 이 값으로 카드를 '철회됨'으로 바꾸고, canReallow가 true면 '다시 허용' 버튼을 보여 줍니다.
 */
public record SharingRevokeResponse(
        Long assignmentId,     // 공유 카드 ID
        SharingStatus status,  // 카드 상태 (REVOKED)
        boolean canReallow     // '다시 허용' 버튼 표시 여부
) {

    /** 철회된 카드 응답 */
    public static SharingRevokeResponse revoked(Long assignmentId, boolean canReallow) {
        return new SharingRevokeResponse(assignmentId, SharingStatus.REVOKED, canReallow);
    }
}
