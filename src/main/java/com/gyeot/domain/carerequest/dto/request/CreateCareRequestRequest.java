package com.gyeot.domain.carerequest.dto.request;

import com.gyeot.domain.carerequest.enums.HelpType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 도움 요청 생성 요청 본문 (POST /api/care-requests, F-05)
 *
 * - 필수값 누락, 타입 불일치(없는 도움 종류 포함), 장소 공백은 공통 400 INVALID_INPUT입니다.
 * - 글자 수 초과(장소 100자, 부탁 내용 500자)와 응답 기한 범위(1~72시간)는 여기서 막지 않고 서비스에서 전용 코드로 응답합니다.
 *   (프론트가 어느 입력창으로 이동할지 나눌 수 있도록 INVALID_INPUT 대신 전용 코드를 줍니다.)
 */
public record CreateCareRequestRequest(
        @NotNull(message = "상황을 선택해 주세요.")
        Long situationId, // 요청이 속할 상황 (내가 만든 진행 중인 상황)

        @NotNull(message = "도움 종류를 선택해 주세요.")
        HelpType helpType,

        @NotNull(message = "시작 시간을 입력해 주세요.")
        LocalDateTime startsAt,

        @NotNull(message = "종료 시간을 입력해 주세요.")
        LocalDateTime endsAt,

        @NotBlank(message = "장소를 입력해 주세요.")
        String place, // 100자 초과는 서비스에서 REQUEST_PLACE_TOO_LONG

        String description, // 선택. 보내지 않거나 빈 문자열·공백이면 null로 저장, 500자 초과는 서비스에서 REQUEST_DESCRIPTION_TOO_LONG

        @NotNull(message = "요청할 사람을 선택해 주세요.")
        Long targetUserId,

        Integer responseDeadlineHours, // 선택. null이면 24시간, 1~72 밖이면 서비스에서 REQUEST_DEADLINE_INVALID

        @NotNull(message = "공개할 정보를 확인해 주세요.")
        List<Long> informationIds // 공개할 항목이 없으면 빈 배열 (D가 검증)
) {
}
