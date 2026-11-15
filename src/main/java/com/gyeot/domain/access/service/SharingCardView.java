package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.carerequest.enums.HelpType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공유 관리 카드 판단 결과입니다. API 응답이 아니라 서비스끼리 주고받는 내부 값입니다. (#46)
 *
 * - 받는 사람 이름은 담지 않습니다. 이름은 A 사용자 조회(#109)로 응답을 만들 때 채웁니다.
 * - situationId는 응답에 내려가지 않는 내부 값입니다. 받은 정보 조회에서 받는 사람의 상황 참여를 확인할 때 씁니다.
 * - 공유 중 목록, 받은 정보, 공유 중 건수가 같은 카드 판단을 쓰도록 이 값으로 모았습니다.
 */
public record SharingCardView(
        Long assignmentId,            // 공유 카드 ID (대상 지정 ID)
        Long requestId,               // 요청 ID
        Long receiverUserId,          // 받는 사람 ID (지금 담당자)
        Long situationId,             // 상황 ID (내부 판단용, 응답에 내려가지 않음)
        HelpType helpType,            // 도움 종류
        LocalDateTime startsAt,       // 요청 시작 시각
        LocalDateTime accessUntil,    // 자동 회수 시각 (요청 종료 시각)
        SharingStatus status,         // 카드 상태
        boolean canReallow,           // '다시 허용' 버튼 표시 여부
        List<SharedItemName> items    // 카드에 보여 줄 항목 (작성순, 삭제 항목 제외, 비어 있지 않음)
) {

    public SharingCardView {
        items = List.copyOf(items); // 만든 뒤 목록이 바뀌지 않게 복사
    }
}
