package com.gyeot.domain.carerequest.dto.request;

import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 요청 응답 요청 본문 (POST /api/care-requests/{requestId}/respond)
 * {"response": "AVAILABLE"}
 * 값이 없거나 AVAILABLE / UNAVAILABLE / CHECK_LATER가 아니면 공통 400 INVALID_INPUT를 반환합니다.
 */
public record RespondRequest(
        @NotNull(message = "응답을 선택해 주세요.")
        AssignmentResponseStatus response // AVAILABLE(수락) / UNAVAILABLE(어려움) / CHECK_LATER(확인 후 답변)
) {
}
