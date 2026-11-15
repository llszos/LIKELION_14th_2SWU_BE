package com.gyeot.domain.situation.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 상황 생성 입력입니다. 날짜시각은 오프셋 없는 Asia/Seoul 현지 시각으로 해석합니다. */
public record SituationCreateRequest(
        @NotBlank(message = "상황 종류는 필수입니다.")
        String situationType,

        @Size(max = 15, message = "기타 상황 종류는 15자 이하여야 합니다.")
        String customSituationType,

        @NotBlank(message = "상황 이름은 필수입니다.")
        @Size(max = 150, message = "상황 이름은 150자 이하여야 합니다.")
        String name,

        @NotNull(message = "참여자 목록은 필수입니다.")
        List<Long> participantIds,

        @NotNull(message = "시작 시각은 필수입니다.")
        LocalDateTime startAt,

        @NotNull(message = "종료 예정 시각은 필수입니다.")
        LocalDateTime endAt
) {
    /** 누락값은 각 필드의 NotNull에서 처리하고, 기간 순서만 교차 검증합니다. */
    @JsonIgnore
    @AssertTrue(message = "상황 시작 시각은 종료 예정 시각보다 빨라야 합니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || startAt.isBefore(endAt);
    }
}
