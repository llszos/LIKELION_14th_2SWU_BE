package com.gyeot.domain.carerequest.enums;

/** 대상 지정 마감 사유 영역입니다.
 * 사용 영역은 request_assignments.close_reason 입니다.
 * Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum AssignmentCloseReason {
    TARGET_CHANGED,          // 대상자 변경
    ACCEPTANCE_WITHDRAWN,    // 시작 전 수락 철회
    RELATIONSHIP_ENDED,      // 관계 해제
    PARTICIPANT_LEFT,        // 상황 이탈
    USER_WITHDRAWN,          // 대상자 회원 탈퇴
    REQUEST_ENDED,           // 요청 취소·만료·완료로 함께 마감
    RESPONSE_DEADLINE_PASSED // 급한 요청의 30분 기한 경과
}

