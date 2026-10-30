package com.gyeot.domain.safetycard.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 안심카드의 큰 분류입니다. (ERD 7.2 주요 Enum 정보 분류)
 * 안심카드 메인(C-00)의 4개 분류(기본정보·건강·생활·미리 남기는 말)와 같습니다.
 * DB에는 저장하지 않고, 항목(InformationType)이 어느 분류에 속하는지 나타낼 때 사용합니다.
 */

@Getter                     // 아래 필드 값을 꺼내는 getter 생성 (예: getDescription())
@RequiredArgsConstructor    // 아래 final 필드(description)를 채우는 생성자 자동 생성. BASIC("기본정보")처럼 쓰려면 필요
public enum InformationCategory {

    // 괄호 안 값: 한글 이름(description)
    BASIC("기본정보"),
    HEALTH("건강"),
    LIFE("생활"),
    ADVANCE_MESSAGE("미리 남기는 말");

    private final String description; // 화면이나 문서에서 알아보기 쉬운 한글 이름 (한 번 정하면 바뀌지 않음)
}