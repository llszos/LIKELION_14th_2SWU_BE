package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;

import java.time.LocalDateTime;
import java.util.List;

/** 로그인 사용자가 발신한 초대 목록입니다. */
public record SentInvitationListResponse(List<Item> items) {

    public record Item(
            Long invitationId,
            InvitationType invitationType,
            InvitationStatus status,
            Responder responder,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
    }

    public record Responder(Long userId, String name) {
    }
}
