package com.gyeot.domain.safetycard.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 안심카드의 큰 분류입니다. (ERD 7.2 주요 Enum 정보 분류)
 * 안심카드 메인(C-00)의 4개 분류(기본 정보, 건강 정보, 생활 정보, 미리 남기는 말)와 같습니다.
 *
 * - description은 응답의 categoryName으로 그대로 내려가 화면의 분류 카드 제목이 됩니다. (노션 D 파트 API 명세 'D 공통 값')
 * - 이름은 디자인(피그마) 표기를 기준으로 합니다. 와이어프레임 초안의 "기본정보", "건강", "생활"에서 11/13 명세 확정 때 바꿨습니다.
 * DB에는 저장하지 않고, 항목(InformationType)이 어느 분류에 속하는지 나타낼 때 사용합니다.
 */

@Getter                     // 아래 필드 값을 꺼내는 getter 생성 (예: getDescription())
@RequiredArgsConstructor    // 아래 final 필드(description)를 채우는 생성자 자동 생성. BASIC("기본 정보")처럼 쓰려면 필요
public enum InformationCategory {

    // 괄호 안 값: 한글 이름(description)
    BASIC("기본 정보"),
    HEALTH("건강 정보"),
    LIFE("생활 정보"),
    ADVANCE_MESSAGE("미리 남기는 말");

    private final String description; // 화면에 보이는 한글 이름 (응답의 categoryName, 디자인 표기 기준. DB에는 저장하지 않아 바꿔도 데이터 영향 없음)
}