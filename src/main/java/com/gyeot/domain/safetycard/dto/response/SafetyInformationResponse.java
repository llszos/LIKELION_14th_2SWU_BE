package com.gyeot.domain.safetycard.dto.response;

import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;

import java.time.LocalDateTime;

/**
 * 항목 하나의 응답입니다. (노션 D 명세 '항목 작성' 성공 응답, #38 항목 수정에서도 사용 예정)
 */
public record SafetyInformationResponse(
        Long informationId,              // 항목 ID
        InformationType informationType, // 항목 유형
        String typeName,                 // 항목 유형 이름
        String title,                    // 항목 이름. title이 없는 유형은 null
        String informationValue,         // 항목 내용 (평문)
        boolean sensitive,               // 민감정보 여부
        LocalDateTime updatedAt          // 최근 갱신 시각
) {

    // 엔티티 → 응답 (유형 이름·민감 여부는 InformationType에서 가져옴)
    public static SafetyInformationResponse from(SafetyInformation information) {
        InformationType type = information.getInformationType();
        return new SafetyInformationResponse(
                information.getId(),
                type,
                type.getDescription(),
                information.getTitle(),
                information.getInformationValue(),
                type.isSensitive(),
                information.getUpdatedAt());
    }
}
