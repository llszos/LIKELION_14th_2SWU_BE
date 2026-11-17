package com.gyeot.domain.access.dto.response;

import com.gyeot.domain.carerequest.enums.HelpType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 받은 정보 조회 응답입니다. (노션 D 파트 API 명세 '받은 정보 조회', R-02-01 '받은 정보' 영역)
 *
 * - 이 사람이 나에게 공유 중인 것 중 지금 볼 수 있는 것만, 요청 단위로 항목 이름만 내려줍니다. 접근 종료 시각 빠른 순입니다.
 * - 항목 내용을 내려주지 않으므로 정보 주인의 접근 이력(VIEWED)에 남지 않습니다. 내용은 '전달받은 정보 조회'에서 봅니다.
 * - 없으면 items는 빈 배열입니다.
 */
public record ReceivedSharingResponse(
        List<ReceivedSharing> items // 받은 공유 목록
) {

    public ReceivedSharingResponse {
        items = List.copyOf(items); // 만든 뒤 목록이 바뀌지 않게 복사
    }

    public record ReceivedSharing(
            Long requestId,                   // 요청 ID. 선택하면 H-03-01(요청 상세)로 이동
            HelpType helpType,                // 도움 종류
            LocalDateTime startsAt,           // 요청 시작 시각
            LocalDateTime accessUntil,        // 접근 종료 시각
            List<SharedItemName> sharedItems  // 공개 항목 (작성순)
    ) {

        public ReceivedSharing {
            sharedItems = List.copyOf(sharedItems); // 만든 뒤 목록이 바뀌지 않게 복사
        }
    }
}
