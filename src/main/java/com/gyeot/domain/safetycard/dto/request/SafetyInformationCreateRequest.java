package com.gyeot.domain.safetycard.dto.request;

import com.gyeot.domain.safetycard.entity.InformationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 항목 작성 요청입니다. (노션 D 파트 API 명세 '항목 작성')
 * 여기서는 모든 유형에 공통인 규칙만 검사하고, 유형마다 다른 규칙(title 유무, 생년월일·혈액형 형식)은 Service에서 검사합니다.
 * 규칙을 어기면 공통 에러 400 INVALID_INPUT
 */
public record SafetyInformationCreateRequest(

        @NotNull // 항목 유형은 필수
        InformationType informationType, // 항목 유형 ('D 공통 값' 항목 유형 표의 값)

        @Size(max = 150) // 항목 이름 최대 150자 (ERD 4.10)
        String title,    // 항목 이름. titleRequired=true인 유형은 필수, 그 외 유형은 null

        @NotBlank         // 비어 있거나 공백만 있으면 안 됨
        @Size(max = 1000) // 항목 내용 최대 1000자
        String informationValue // 항목 내용 (생년월일은 YYYY-MM-DD, 혈액형은 A/B/O/AB)
) {
}
