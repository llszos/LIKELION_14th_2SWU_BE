package com.gyeot.domain.safetycard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 항목 수정 요청입니다. (노션 D 파트 API 명세 '항목 수정')
 *
 * - 이름과 내용만 바꿀 수 있습니다. 항목 유형은 바꿀 수 없어 받지 않습니다. (다른 유형이 필요하면 새로 작성)
 * - 공통 규칙은 작성 요청(SafetyInformationCreateRequest)과 같고, 유형마다 다른 규칙은 Service에서 작성과 같은 방식으로 검사합니다.
 * - 규칙을 어기면 공통 에러 400 INVALID_INPUT
 */
public record SafetyInformationUpdateRequest(

        @Size(max = 150) // 항목 이름 최대 150자 (ERD 4.10, 작성과 같음)
        String title,    // 항목 이름. titleRequired=true인 유형은 필수, 그 외 유형은 null

        @NotBlank         // 비어 있거나 공백만 있으면 안 됨
        @Size(max = 1000) // 항목 내용 최대 1000자
        String informationValue // 항목 내용 (생년월일은 YYYY-MM-DD, 혈액형은 A/B/O/AB)
) {
}
