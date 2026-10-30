package com.gyeot.domain.carerequest.enums;

/** 도움 유형 영역입니다.
 *  사용 컬럼은 care_requests.help_type 입니다.
 *  Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */

public enum HelpType {
    HOSPITAL, // 병원 동행
    MEAL, // 식사
    GROCERY, // 장보기
    PET_CARE, // 반려동물 돌봄
    CHECK_IN, // 연락/안부
    OTHER // 기타
}
