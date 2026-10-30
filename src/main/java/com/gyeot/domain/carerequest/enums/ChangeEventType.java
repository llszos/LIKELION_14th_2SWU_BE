package com.gyeot.domain.carerequest.enums;

/** 요청 변경 이벤트 유형 영역입니다.
 *  사용 컬럼은 request_change_events.event_type 입니다.
 *  Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum ChangeEventType {
    RESPONSE_CHANGED,           // 응답 변경 (대상자가 '도움 가능 / 어려움 / 확인 후 답변' 중 하나를 고른 것)
    TARGET_CHANGED,             // 대상 변경 (요청자가 다른 사람에게 다시 부탁한 것)
    REQUEST_UPDATED,            // 요청 수정 (요청자가 수락 전에 일정·장소·공개 항목을 바로 고친 것)
    RECONFIRMATION_REQUESTED,   // 재확인 요청 (요청자가 수락된 요청을 수정해서 담당자에게 확인을 요청한 것)
    RECONFIRMATION_ACCEPTED,    // 재확인 수락 (담당자가 바뀐 내용을 확인해서 요청에 반영된 것)
    RECONFIRMATION_REJECTED,    // 재확인 거절 (담당자가 바뀐 내용을 거절한 것. 요청은 취소됨)
    RECONFIRMATION_INVALIDATED, // 재확인 무효화 (담당자가 답하기 전에 변경 제안이 효력을 잃은 것. 기존 내용은 그대로 유지됨)
    REQUEST_STATUS_CHANGED,     // 요청 상태 변경 (요청 상태(요청됨·수락됨·진행 중·완료·취소·만료)가 바뀐 것)
    ASSIGNMENT_CLOSED;          // 지정 마감 (대상 변경이나 요청 종료가 아닌 다른 이유로 대상자 지정이 끝난 것)
}
