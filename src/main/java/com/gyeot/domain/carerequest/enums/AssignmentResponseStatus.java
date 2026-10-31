package com.gyeot.domain.carerequest.enums;

/** 응답 영역입니다.
 *  사용 컬럼은 request_assignments.response_status 입니다.
 *  Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum AssignmentResponseStatus {
    AVAILABLE, // 가능
    UNAVAILABLE, // 어려움
    CHECK_LATER // 확인 후 답변
}
