package com.gyeot.domain.safetycard.dto.response;

import com.gyeot.domain.safetycard.entity.InformationCategory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 안심카드 메인 조회 응답입니다. (노션 D 명세 '안심카드 메인 조회', 화면 C-00)
 * 분류 카드 4개에 표시할 최근 갱신일과, 상단의 공유 중 건수를 내려줍니다.
 * 항목 수는 디자인에 없어 내려주지 않습니다.
 */
public record SafetyCardSummaryResponse(
        List<CategorySummary> categories, // 분류 4개 (BASIC → HEALTH → LIFE → ADVANCE_MESSAGE 고정)
        long sharingCount                 // 지금 공유 중 건수 ((요청, 담당자) 묶음 수, 홈 F-34와 같은 기준)
) {

    public record CategorySummary(
            InformationCategory category, // 분류
            String categoryName,          // 분류 이름 (분류 카드 제목)
            LocalDateTime lastUpdatedAt   // 분류 안 항목 중 가장 최근 수정 시각. 항목이 없으면 null (FE는 갱신일 없이 표시)
    ) {
    }
}
