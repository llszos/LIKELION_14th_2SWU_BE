package com.gyeot.domain.access.dto.response;

import com.gyeot.domain.safetycard.entity.InformationType;

/**
 * 공유 관리 화면에서 보여 주는 공개 항목 이름입니다. 항목 내용은 담지 않습니다. (C-06, R-02-01 태그)
 *
 * - 공유 중 목록, 받은 정보 조회, 다시 허용 응답에서 함께 씁니다.
 * - 삭제한 항목은 이 목록에 들어오지 않으므로, title이 null이면 title이 없는 유형입니다. (11/14 FE 답변)
 * - 내용을 내려주지 않으므로 접근 이력(VIEWED)을 남기지 않습니다.
 */
public record SharedItemName(
        InformationType informationType, // 항목 유형
        String typeName,                 // 항목 유형 이름 (화면 태그, 예: "복용약")
        String title                     // 항목 이름. title이 없는 유형은 null
) {

    /** 항목 유형과 title로 만듭니다. 유형 이름은 InformationType의 이름을 그대로 씁니다. */
    public static SharedItemName of(InformationType informationType, String title) {
        return new SharedItemName(informationType, informationType.getDescription(), title);
    }
}
