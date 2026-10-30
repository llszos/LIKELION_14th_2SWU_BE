package com.gyeot.domain.carerequest.enums;

/** 요청 취소 사유 영역입니다.
 * 사용 영역은 care_requests.cancel_reason 입니다.
 * Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum CancelReason {
    REQUESTER_CANCELED, // 요청자 직접 취소
    ASSIGNEE_LEFT, // 진행 중 담당자 이탈
    RELATIONSHIP_ENDED, // 관게 해제
    SITUATION_ENDED, // 상황 종료
    USER_WITHDRAWN, // 회원 탈퇴
    RECONFIRMATION_REJECTED // 재확인 거절
}