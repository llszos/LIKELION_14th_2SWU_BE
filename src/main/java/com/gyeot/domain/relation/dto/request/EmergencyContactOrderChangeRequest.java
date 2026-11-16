package com.gyeot.domain.relation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 보호자와 추가 비상연락 대상의 순서를 함께 변경하는 요청입니다. */
public record EmergencyContactOrderChangeRequest(
        @NotNull List<@NotNull Long> guardianUserIds,
        @NotNull List<@NotNull Long> emergencyContactUserIds
) {
}
