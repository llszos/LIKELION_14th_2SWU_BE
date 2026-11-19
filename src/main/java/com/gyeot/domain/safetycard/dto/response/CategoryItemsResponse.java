package com.gyeot.domain.safetycard.dto.response;

import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 분류별 항목 조회 응답입니다. (노션 D 파트 API 명세 '분류별 항목 조회', 화면 C-01·C-02·C-04·C-05)
 * 분류 안 모든 항목 유형을 내려주고, 등록한 항목이 없는 유형은 items가 빈 배열입니다. (FE가 '추가하기' 영역을 그림)
 */
public record CategoryItemsResponse(
        InformationCategory category, // 분류
        String categoryName,          // 분류 이름 (화면 상단)
        List<TypeItems> types         // 분류 안 항목 유형 (InformationType 선언 순서)
) {

    public record TypeItems(
            InformationType informationType, // 항목 유형
            String typeName,                 // 항목 유형 이름 (섹션 제목)
            boolean sensitive,               // 민감정보 여부 ('민감' 표시)
            boolean multiple,                // 여러 개 등록 가능 여부 (false이고 항목이 있으면 FE가 '추가하기'를 숨김)
            boolean titleRequired,           // 작성 시 이름(title) 입력 칸을 보여 줄지
            List<Item> items                 // 등록한 항목 (작성순)
    ) {
    }

    public record Item(
            Long informationId,       // 항목 ID
            String title,             // 항목 이름. title이 없는 유형은 null
            String informationValue,  // 항목 내용 (복호화된 평문)
            LocalDateTime updatedAt   // 최근 갱신 시각 (카드의 날짜 배지)
    ) {
    }
}
