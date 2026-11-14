package com.gyeot.domain.relation.dto.request;

import com.gyeot.domain.relation.entity.InvitationType;
import jakarta.validation.constraints.NotNull;

/** 초대 링크 생성 요청입니다. */
public record InvitationCreateRequest(
        @NotNull InvitationType invitationType,
        String personalLabel,
        String message,
        Boolean promiseConfirmed
) {
    // personalLabel은 나의 사람 초대에, message와 promiseConfirmed는 보호자 요청에 사용합니다.
}
