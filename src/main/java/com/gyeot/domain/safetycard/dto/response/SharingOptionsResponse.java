package com.gyeot.domain.safetycard.dto.response;

import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공개 항목 선택지 조회 응답입니다. (노션 D 명세 '공개 항목 선택지 조회')
 * 분류 4개를 BASIC → HEALTH → LIFE → ADVANCE_MESSAGE 순서로 항상 내려주고, 항목이 없는 분류는 빈 배열입니다.
 */
public record SharingOptionsResponse(
        List<CategoryOption> categories // 분류별 항목
) {

    public record CategoryOption(
            InformationCategory category, // 분류
            String categoryName,          // 분류 이름 (분류 카드 제목)
            List<ItemOption> items        // 등록한 항목 (작성순)
    ) {
    }

    public record ItemOption(
            Long informationId,           // 항목 ID. 선택한 ID를 C 요청 API에 그대로 전달
            InformationType informationType, // 항목 유형
            String typeName,              // 항목 유형 이름
            String title,                 // 항목 이름. title이 없는 유형은 null
            String informationValue,      // 항목 내용 (본인 화면이라 내용 포함)
            boolean sensitive,            // 민감정보 여부
            boolean selected,             // 기본 선택 여부
            LocalDateTime updatedAt       // 최근 갱신 시각
    ) {
    }
}
