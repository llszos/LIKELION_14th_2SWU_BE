package com.gyeot.domain.carerequest.dto.request;

import jakarta.validation.constraints.AssertTrue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 요청 수정 요청 본문 (PATCH /api/care-requests/{requestId}, F-29)
 *
 * - 바꿀 필드만 보냅니다. 하나 이상 필수이고, 하나도 없으면 공통 400 INVALID_INPUT를 반환합니다.
 * - 장소 글자 수 초과는 여기서 막지 않고 서비스에서 400 REQUEST_PLACE_TOO_LONG으로 응답합니다.
 *   (공통 INVALID_INPUT이 아니라 전용 코드를 줘야 프론트가 장소 입력창으로 이동할 수 있습니다.)
 * - 도움 종류, 부탁 내용, 대상, 응답 기한은 수정할 수 없어 받지 않습니다.
 * - 재확인 대기 중이면 대기 중 수정안의 값(latestProposal.after)을 이어서 고치므로, 수정안에서 바꾼 필드도 함께 보냅니다.
 *   바뀐 필드는 서버가 원래 요청 값과 비교해 정합니다.
 */
public record UpdateCareRequestRequest(
        LocalDateTime startsAt, // 변경 후 시작 일시
        LocalDateTime endsAt, // 변경 후 종료 일시
        String place, // 변경 후 장소 (빈 값·공백 불가, 100자 초과는 서비스에서 REQUEST_PLACE_TOO_LONG)

        List<Long> informationIds // 변경 후 공개할 전체 항목 ID. 바꾸지 않으면 보내지 않음, 모두 빼려면 빈 배열
) {

    /** 바꿀 필드가 하나 이상 있는지 */
    @AssertTrue(message = "수정할 항목을 하나 이상 보내 주세요.")
    public boolean isAnyFieldPresent() {
        return startsAt != null || endsAt != null || place != null || informationIds != null;
    }

    /** 장소를 보냈다면 비어 있지 않은지 (일반 요청은 장소 필수) */
    @AssertTrue(message = "장소를 입력해 주세요.")
    public boolean isPlaceNotBlank() {
        return place == null || !place.isBlank();
    }
}