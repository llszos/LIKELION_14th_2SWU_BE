package com.gyeot.domain.access.dto.response;

/**
 * 공유 카드(요청, 담당자 묶음)의 상태입니다. (노션 D 파트 API 명세 '공유 중 목록 조회', F-30)
 *
 * - 응답의 status로 내려가고, FE는 이 값으로 '직접 철회' 버튼과 '철회됨' 표시를 고릅니다.
 * - DB에는 저장하지 않고, 조회할 때마다 카드의 권한 상태로 판단합니다.
 * - 요청이 끝나는 등 자동으로 끝난 공유는 목록에서 빠지므로 상태 값이 따로 없습니다.
 */
public enum SharingStatus {

    SHARING, // 공유 중: 담당자가 지금 볼 수 있는 항목이 있음
    REVOKED  // 철회됨: 정보 주인이 직접 철회했고 요청은 아직 진행 중 (다시 허용 가능 여부는 canReallow)
}
