package com.gyeot.domain.relation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 보호자와 추가 비상연락 대상의 순서를 함께 변경하는 요청입니다. */
public record EmergencyContactOrderChangeRequest(
        @NotNull(message = "입력값이 올바르지 않습니다.")
        List<@NotNull(message = "입력값이 올바르지 않습니다.") Long> guardianUserIds,
        @NotNull(message = "입력값이 올바르지 않습니다.")
        List<@NotNull(message = "입력값이 올바르지 않습니다.") Long> emergencyContactUserIds
) {
}
