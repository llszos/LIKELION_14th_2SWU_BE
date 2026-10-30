package com.gyeot.domain.safetycard.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 안심카드에 등록할 수 있는 항목입니다. (ERD 4.10 safety_information 안심카드 항목 표)
 * 항목마다 속한 분류, 한글 이름, 민감정보 여부, 여러 개 등록 가능 여부를 함께 가집니다.
 * DB의 information_type 컬럼에는 이 이름(예: MEDICATION)이 문자열로 저장됩니다.
 * 분류는 항목에 이미 정해져 있으므로 DB에 따로 저장하지 않습니다. (예: MEDICATION → HEALTH)
 */

@Getter                  // 아래 필드 값을 꺼내는 getter 생성 (예: getCategory(), getDescription(), isSensitive(), isMultiple())
@RequiredArgsConstructor // 아래 final 필드 4개(category, description, sensitive, multiple)를 채우는 생성자 자동 생성. MEDICATION(...)처럼 쓰려면 필요
public enum InformationType {

    // 괄호 안 값 순서: (분류, 한글 이름, 민감정보 여부, 여러 개 등록 가능 여부)

    // 기본정보
    BIRTH_DATE(InformationCategory.BASIC, "생년월일", false, false),
    BLOOD_TYPE(InformationCategory.BASIC, "혈액형", false, false),
    BASIC_MEMO(InformationCategory.BASIC, "기타 메모", false, false),

    // 건강
    DISEASE(InformationCategory.HEALTH, "질환", true, true),
    MEDICATION(InformationCategory.HEALTH, "복용약", true, true),
    ALLERGY(InformationCategory.HEALTH, "알레르기", true, true),
    CAUTION(InformationCategory.HEALTH, "주의사항", false, true),
    HEALTH_MEMO(InformationCategory.HEALTH, "기타 메모", false, false),

    // 생활
    ADDRESS(InformationCategory.LIFE, "주소", true, true),
    ENTRY_METHOD(InformationCategory.LIFE, "출입 방법", true, true),
    PET(InformationCategory.LIFE, "반려동물", false, true),
    LIFE_MEMO(InformationCategory.LIFE, "기타 메모", false, false),

    // 미리 남기는 말
    PREFERENCE_REQUEST(InformationCategory.ADVANCE_MESSAGE, "선호·부탁", false, true),
    DOCUMENT_LOCATION(InformationCategory.ADVANCE_MESSAGE, "준비 문서 보관 위치", false, true),
    ADVANCE_MEMO(InformationCategory.ADVANCE_MESSAGE, "기타 메모", false, false);

    private final InformationCategory category; // 이 항목이 속한 분류
    private final String description;           // 화면이나 문서에서 알아보기 쉬운 한글 이름
    private final boolean sensitive;            // 민감정보 여부 (질환·복용약·알레르기·주소·출입 방법)
    private final boolean multiple;             // true면 여러 개 등록 가능, false면 1개만 등록 (생년월일·혈액형·분류별 기타 메모)
    // boolean 필드는 Lombok이 getter 이름을 get이 아니라 is로 만듦 → isSensitive(), isMultiple()
}