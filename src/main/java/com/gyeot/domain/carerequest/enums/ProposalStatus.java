package com.gyeot.domain.carerequest.enums;

/**
 * 재확인 상태 영역입니다.
 * 사용 컬럼은 request_change_proposals.status 입니다.
 * Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum ProposalStatus {
    PENDING, // 대기
    ACCEPTED, // 수락
    REJECTED, // 거절
    INVALIDATED // 무효
}
