package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.InformationType;

/**
 * 안심카드 항목의 ID와 유형만 담은 조회 결과입니다. (C 요청 상세의 항목 이름 표시용)
 * 암호화된 title과 information_value를 담지 않아, 조회해도 복호화가 일어나지 않습니다.
 */
public record InformationIdAndType(
        Long id,                        // 항목 ID
        InformationType informationType // 항목 유형 (이름은 InformationType의 description)
) {
}
