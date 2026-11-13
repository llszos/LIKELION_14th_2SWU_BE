package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.RequestType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 담당자 없음 안내 목록 (data) - GET /api/care-requests/no-assignee-notices (F-14, M-01)
 * items: 지금 띄울 모달 목록. 급한 요청 먼저(지정 시각 빠른 순), 그다음 일반 요청(시작 시각 빠른 순). 없으면 빈 배열
 */
public record NoAssigneeNoticesResponse(List<Item> items) {

    public NoAssigneeNoticesResponse {
        items = List.copyOf(items);
    }

    /**
     * 모달 하나
     * - startsAt: 급한 요청은 null
     * - place: 장소. 모달 카드 "9/30 20:00 · 슈슈병원"에 표시 (도움 요청 생성·상세 조회의 place와 같은 값)
     * - target: 담당자가 없어진 대상 (모달 제목 "병원 동행 · 민재")
     * - reason: 담당자 없음 사유. FE는 모달 문구에 쓰고, 닫기 API에 그대로 보냄
     */
    public record Item(
            Long requestId,
            RequestType requestType,
            HelpType helpType,
            LocalDateTime startsAt,
            String place,
            Target target,
            NoAssigneeReason reason
    ) {
    }

    /**
     * 대상자
     * TODO: A의 사용자 기본 정보 일괄 조회(#57)가 들어오면 name을 채웁니다. 지금은 null입니다.
     *       응답 모양은 명세와 같게 미리 두어, FE가 지금부터 같은 모양으로 개발할 수 있게 했습니다.
     */
    public record Target(Long userId, String name) {
    }
}