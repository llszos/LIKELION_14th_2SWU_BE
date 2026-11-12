package com.gyeot.domain.relation.dto.response;

import java.time.LocalDateTime;

/** 새 초대 링크를 공유하는 데 필요한 값입니다. */
public record InvitationCreateResponse(
        Long invitationId,
        String invitationUrl,
        LocalDateTime expiresAt
) {
}