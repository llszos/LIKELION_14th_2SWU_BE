package com.gyeot.domain.carerequest.enums;

/** 요청 유형 영역입니다.
 *  사용 컬럼은 care_requests.request_type 입니다.
 *  Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */

public enum RequestType {
    GENERAL, // 일반 요청
    URGENT // 급한 요청
}
