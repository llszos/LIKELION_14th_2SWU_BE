package com.gyeot.domain.safetycard.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 안심카드에 등록할 수 있는 항목입니다. (ERD 4.10 safety_information 안심카드 항목 표)
 * 항목마다 속한 분류, 한글 이름, 민감정보 여부, 여러 개 등록 가능 여부를 함께 가집니다.
 * DB의 information_type 컬럼에는 이 이름(예: MEDICATION)이 문자열로 저장됩니다.
 * 분류는 항목에 이미 정해져 있으므로 DB에 따로 저장하지 않습니다. (예: MEDICATION → HEALTH)
 *
 * - 한글 이름(description)은 응답의 typeName으로 그대로 내려가 화면의 섹션 제목과 부제가 됩니다.
 *   디자인(피그마) 표기를 기준으로 하며, ALLERGY는 디자인에 맞춰 "알러지"입니다. (11/13 명세 확정)
 * - DB에는 enum 이름(ALLERGY)만 저장하므로 한글 이름을 바꿔도 저장된 데이터에는 영향이 없습니다.
 */

@Getter                  // 아래 필드 값을 꺼내는 getter 생성 (예: getCategory(), getDescription(), isSensitive(), isMultiple(), isTitleRequired())
@RequiredArgsConstructor // 아래 final 필드 5개(category, description, sensitive, multiple, titleRequired)를 채우는 생성자 자동 생성. MEDICATION(...)처럼 쓰려면 필요
public enum InformationType {

    // 괄호 안 값 순서: (분류, 한글 이름, 민감정보 여부, 여러 개 등록 가능 여부, title 입력 여부)

    // 기본 정보
    BIRTH_DATE(InformationCategory.BASIC, "생년월일", false, false, false),
    BLOOD_TYPE(InformationCategory.BASIC, "혈액형", false, false, false),

    // 건강 정보
    DISEASE(InformationCategory.HEALTH, "질환", true, true, true),
    MEDICATION(InformationCategory.HEALTH, "복용약", true, true, true),
    ALLERGY(InformationCategory.HEALTH, "알러지", true, true, true),
    CAUTION(InformationCategory.HEALTH, "주의사항", false, true, true),

    // 생활 정보 (주소, 출입 방법은 디자인 C-04 '집 정보'에 '추가하기'가 없어 1개만 등록, 기획 답변 11/8)
    ADDRESS(InformationCategory.LIFE, "주소", true, false, false),
    ENTRY_METHOD(InformationCategory.LIFE, "출입 방법", true, false, false),
    PET(InformationCategory.LIFE, "반려동물", false, true, true),

    // 미리 남기는 말
    PREFERENCE_REQUEST(InformationCategory.ADVANCE_MESSAGE, "선호·부탁", false, true, true),
    DOCUMENT_LOCATION(InformationCategory.ADVANCE_MESSAGE, "준비 문서 보관 위치", false, true, true);

    // 분류별 기타 메모는 두지 않음 (기획 답변 11/8: 현재 화면 범위에서는 메모가 없는 편이 사용성이 좋음)

    private final InformationCategory category; // 이 항목이 속한 분류
    private final String description;           // 화면에 보이는 한글 이름 (응답의 typeName, 디자인 표기 기준)
    private final boolean sensitive;            // 민감정보 여부 (질환, 복용약, 알러지, 주소, 출입 방법)
    private final boolean multiple;             // true면 여러 개 등록 가능, false면 1개만 등록 (생년월일, 혈액형, 주소, 출입 방법)
    private final boolean titleRequired;        // true면 title(항목 이름) 필수, false면 title 없이 내용만 저장 (생년월일, 혈액형, 주소, 출입 방법)
    // boolean 필드는 Lombok이 getter 이름을 get이 아니라 is로 만듦 → isSensitive(), isMultiple(), isTitleRequired()
}
