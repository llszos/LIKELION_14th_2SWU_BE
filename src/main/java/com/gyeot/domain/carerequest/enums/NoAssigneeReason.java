package com.gyeot.domain.carerequest.enums;

/** 담당자 없음 사유 영역입니다. (F-14, M-01 트리거)
 * 사용 컬럼은 request_assignments.notice_reason 입니다. (모달을 닫을 때의 사유)
 */
public enum NoAssigneeReason {
    UNAVAILABLE, // 어려움
    DEADLINE_PASSED, // 응답 기한 경과 (급한 요청 30분 경과 포함)
    ACCEPTANCE_WITHDRAWN, // 수락 철회
    PARTICIPANT_LEFT, // 상황 나가기·제외
    RELATIONSHIP_ENDED, // 관계 해제
    USER_WITHDRAWN; // 대상자 회원 탈퇴

    /**
     * 지정 마감 사유 → 담당자 없음 사유
     * 대상 변경·요청 종료는 담당자 없음이 아니므로 null
     * */
    public static NoAssigneeReason from(AssignmentCloseReason closeReason) {
        return switch (closeReason) {
            case ACCEPTANCE_WITHDRAWN -> ACCEPTANCE_WITHDRAWN;
            case PARTICIPANT_LEFT -> PARTICIPANT_LEFT;
            case RELATIONSHIP_ENDED -> RELATIONSHIP_ENDED;
            case USER_WITHDRAWN -> USER_WITHDRAWN;
            case RESPONSE_DEADLINE_PASSED -> DEADLINE_PASSED;
            case TARGET_CHANGED, REQUEST_ENDED -> null;
        };
    }
}