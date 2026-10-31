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
    REQUEST_ENDED      // 요청 취소
}
