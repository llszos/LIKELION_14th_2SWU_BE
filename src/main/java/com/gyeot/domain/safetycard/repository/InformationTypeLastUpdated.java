package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.InformationType;

import java.time.LocalDateTime;

/**
 * 항목 유형별 가장 최근 수정 시각입니다. (안심카드 메인 조회용 조회 결과)
 * 암호화된 title·information_value를 담지 않아, 조회해도 복호화가 일어나지 않습니다.
 */
public record InformationTypeLastUpdated(
        InformationType informationType, // 항목 유형
        LocalDateTime updatedAt          // 그 유형 항목 중 가장 최근 수정 시각
) {
}
