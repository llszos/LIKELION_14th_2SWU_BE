package com.gyeot.domain.carerequest.enums;

/** 요청 상태 영역입니다.
 *  사용 영역은 care_requests.status 입니다.
 *  Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum CareRequestStatus {
    REQUESTED, // 요청
    ACCEPTED, // 수락
    IN_PROGRESS, // 진행 중
    COMPLETED, // 완료
    CANCELED, // 취소
    EXPIRED; // 만료

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == EXPIRED;
    }
}
