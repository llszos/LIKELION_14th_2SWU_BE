package com.gyeot.domain.carerequest.dto.request;

import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import jakarta.validation.constraints.NotNull;

/**
 * 담당자 없음 안내 닫기 요청 본문 (POST /api/care-requests/{requestId}/no-assignee-notice/dismiss)
 * {"reason": "UNAVAILABLE"} - 안내 목록에서 받은 reason을 그대로 보냅니다.
 * 값이 없거나 사유 값이 아니면 공통 400 INVALID_INPUT
 */
public record DismissNoticeRequest(
        @NotNull(message = "모달에 표시한 사유가 필요합니다.")
        NoAssigneeReason reason
) {
}