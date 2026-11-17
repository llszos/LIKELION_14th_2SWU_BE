package com.gyeot.domain.access.dto.response;

import com.gyeot.domain.carerequest.enums.HelpType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공유 중 목록 조회 응답입니다. (노션 D 파트 API 명세 '공유 중 목록 조회', C-06, R-02-01)
 *
 * - 내 정보를 볼 수 있는 사람을 (요청, 담당자) 카드 단위로 내려줍니다. 자동 회수 시각(accessUntil) 빠른 순입니다.
 * - 삭제한 항목은 sharedItems에서 빠지고, 남은 항목이 없는 카드는 목록에서 빠집니다. (11/14 FE 답변)
 * - 없으면 items는 빈 배열입니다.
 */
public record SharingListResponse(
        List<SharingCard> items // 공유 카드 목록
) {

    public SharingListResponse {
        items = List.copyOf(items); // 만든 뒤 목록이 바뀌지 않게 복사
    }

    public record SharingCard(
            Long assignmentId,                // 공유 카드 ID (대상 지정 ID). 직접 철회와 다시 허용에 사용
            Long requestId,                   // 요청 ID
            Receiver receiver,                // 받는 사람
            HelpType helpType,                // 도움 종류
            LocalDateTime startsAt,           // 요청 시작 시각
            LocalDateTime accessUntil,        // 자동 회수 시각 (요청 종료 시각)
            SharingStatus status,             // 카드 상태 (공유 중, 철회됨)
            boolean canReallow,               // '다시 허용' 버튼 표시 여부
            List<SharedItemName> sharedItems  // 공개 항목 (작성순, 삭제한 항목 제외)
    ) {

        public SharingCard {
            sharedItems = List.copyOf(sharedItems); // 만든 뒤 목록이 바뀌지 않게 복사
        }
    }

    public record Receiver(
            Long userId, // 받는 사람 ID
            String name  // 받는 사람 이름. 탈퇴했으면 "탈퇴한 사용자"
    ) {
    }
}
