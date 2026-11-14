package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;

import java.time.LocalDateTime;

/** 초대 링크 화면에 표시할 정보입니다. */
public record InvitationDetailsResponse(
        InvitationType invitationType,
        Inviter inviter,
        String defaultMessage,
        String message,
        LocalDateTime expiresAt,
        InvitationStatus status,
        boolean canAccept,
        String unavailableReason
) {

    /** 초대 화면에 표시할 발신자 정보입니다. */
    public record Inviter(Long userId, String name) {
    }
}
