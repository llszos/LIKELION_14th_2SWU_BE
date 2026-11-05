package com.gyeot.domain.carerequest.enums;

/**
 * request_change_events — 요청 변경 이벤트에서
 * 기록 대상과 상세 저장 범위와 관련된 Enum입니다.
 * 무효화 사유
 */
public enum InvalidationReason {
    DEADLINE_PASSED,   // 재확인 기한 만료
    REQUEST_STARTED,   // 재확인 대기 중 요청 시작
    ASSIGNMENT_CLOSED, // 담당자 지정 마감 (수락 철회, 관계 해제, 상황 이탈 등)
    REQUEST_ENDED,      // 요청 취소
    REPLACED_BY_NEW_PROPOSAL, // 재확인 대기 중 다시 수정해 새 제안으로 대체
    REQUESTER_WITHDRAWN       // 요청자가 대기 중인 수정안을 철회
}
