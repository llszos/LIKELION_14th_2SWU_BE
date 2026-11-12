package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;

import java.time.LocalDateTime;

/** 초대 링크 화면에 표시할 정보입니다. */
public record InvitationDetailsResponse(
        Long invitationId,
        InvitationType invitationType,
        String inviterName,
        InvitationStatus status,
        LocalDateTime expiresAt,
        boolean canRespond,
        String message,
        Boolean guardianLimitReached
) {
}
