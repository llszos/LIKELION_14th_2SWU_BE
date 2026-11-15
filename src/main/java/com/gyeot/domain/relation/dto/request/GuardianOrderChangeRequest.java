package com.gyeot.domain.relation.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 보호자끼리의 연락 순서 변경 요청입니다. */
public record GuardianOrderChangeRequest(
        @NotEmpty
        @Size(max = 3)
        List<@NotNull Long> targetUserIds
) {
}
