package com.gyeot.domain.carerequest.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 다시 부탁하기 요청 본문 (POST /api/care-requests/{requestId}/assignments, F-05·F-14)
 *
 * - 필수값 누락, 타입 불일치는 공통 400 INVALID_INPUT입니다.
 * - 응답 기한 범위(1~72시간)는 여기서 막지 않고 서비스에서 전용 코드 REQUEST_DEADLINE_INVALID로 응답합니다.
 */
public record ReassignRequest(
        @NotNull(message = "요청할 사람을 선택해 주세요.")
        Long targetUserId, // 새 대상 (상황 참여자 중 나의 사람·서로의 보호자, 이 요청의 이전 대상이 아닌 사람)

        Integer responseDeadlineHours, // 선택. null이면 24시간, 시작까지 남은 시간보다 길면 시작 시각으로 제한

        @NotNull(message = "공개할 정보를 확인해 주세요.")
        List<Long> informationIds // 새 대상에게 공개할 항목 전체 (없으면 빈 배열, D가 검증)
) {
}
