package com.gyeot.domain.relation.dto.request;

import com.gyeot.domain.relation.entity.InvitationType;
import jakarta.validation.constraints.NotNull;

/** 초대 링크 생성 요청입니다. */
public record InvitationCreateRequest(
        @NotNull InvitationType invitationType,
        String message
) {
    // 나의 사람 초대 API는 message를 사용하지 않으며, 보호자 요청 API에서만 편지를 사용합니다.
}