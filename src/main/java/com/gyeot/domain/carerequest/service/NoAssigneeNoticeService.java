package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse;
import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse.Item;
import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse.Target;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 담당자 없음 안내(모달 M-01) 목록 조회와 닫기를 담당합니다. (#74, F-14)
 *
 * 사유마다 한 번만 띄우는 규칙(F-14, 기획 답변)은 엔티티 RequestAssignment가 가지고 있습니다.
 * - currentNoAssigneeReason(now): 지금의 담당자 없음 사유 (어려움, 기한 경과, 수락 철회, 상황 나가기 등)
 * - needsNoAssigneeNotice(now): 담당자 없음이고, 그 사유로 아직 모달을 닫은 적이 없는지
 * - closeNotice(사유, now): 모달을 닫은 사유를 기록
 * 이 서비스는 "어떤 요청의 어떤 지정을 볼지"와 "누가 할 수 있는지"만 정합니다.
 *
 * 담당자 없음 판단은 요청의 가장 최근 지정으로 합니다. (RequestDisplayStatus와 같은 기준)
 */
@Service
@RequiredArgsConstructor
public class NoAssigneeNoticeService {
    private final CareRequestFinder careRequestFinder;
    private final RequestAssignmentRepository assignmentRepository;
    private final Clock clock;

    // === 담당자 없음 안내 목록 (GET /api/care-requests/no-assignee-notices) ===

    /**
     * 내가 보낸 요청 중 지금 모달을 띄워야 하는 것
     * - 요청됨 상태인 요청의 가장 최근 지정이 담당자 없음이고, 그 사유로 아직 닫지 않았을 때
     * - 일반 요청은 시작 시각 전까지만 (시작이 지나면 곧 만료되고, 다시 부탁할 수도 없음, F-05)
     * 기한 경과는 현재 시각에 따라 달라져 DB 조건만으로 거를 수 없으므로, 조회한 뒤 자바에서 계산합니다.
     */
    @Transactional(readOnly = true)
    public NoAssigneeNoticesResponse getNotices(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 내 요청됨 요청들의 지정 전체(요청과 함께) → 요청 ID별 가장 최근 지정
        Map<Long, RequestAssignment> latestByRequestId = assignmentRepository
                .findAllWithRequestByRequesterIdAndStatus(userId, CareRequestStatus.REQUESTED)
                .stream()
                .collect(Collectors.toMap(a -> a.getCareRequest().getId(), a -> a,
                        (a, b) -> a.getAssignmentOrder() > b.getAssignmentOrder() ? a : b)); // 같은 요청이면 순서가 큰 쪽

        List<Item> items = latestByRequestId.values().stream()
                .filter(latest -> latest.needsNoAssigneeNotice(now))
                .filter(latest -> isBeforeStart(latest.getCareRequest(), now))
                .sorted(MODAL_ORDER)
                .map(latest -> toItem(latest, now))
                .toList();

        return new NoAssigneeNoticesResponse(items);
    }

    /** 일반 요청은 시작 시각 전까지만 안내 (급한 요청은 시작 시각이 없음) */
    private static boolean isBeforeStart(CareRequest request, LocalDateTime now) {
        return request.isUrgent() || now.isBefore(request.getStartsAt());
    }

    /**
     * 모달 순서 (F-14 "여러 건일 때"): 급한 요청 먼저(지정 시각 빠른 순), 그다음 일반 요청(시작 시각 빠른 순)
     * 같으면 요청 ID 순으로 두어 매번 같은 순서가 나오게 합니다.
     * (가장 최근 지정을 기준으로 비교하고, 요청은 지정에서 꺼냅니다)
     */
    private static final Comparator<RequestAssignment> MODAL_ORDER =
            Comparator.<RequestAssignment, Boolean>comparing(a -> !a.getCareRequest().isUrgent()) // false(급한 요청)가 먼저
                    .thenComparing(a -> a.getCareRequest().isUrgent()
                            ? a.getCreatedAt()                     // 급한 요청: 지정 시각
                            : a.getCareRequest().getStartsAt())    // 일반 요청: 시작 시각
                    .thenComparing(a -> a.getCareRequest().getId());

    private Item toItem(RequestAssignment latest, LocalDateTime now) {
        CareRequest request = latest.getCareRequest();
        return new Item(request.getId(), request.getRequestType(), request.getHelpType(), request.getStartsAt(), request.getPlace(),
                new Target(latest.getTargetUserId(), null), // TODO: #57 사용자 조회가 들어오면 이름을 채움
                latest.currentNoAssigneeReason(now));
    }

    // === 담당자 없음 안내 닫기 (POST /api/care-requests/{requestId}/no-assignee-notice/dismiss) ===

    /**
     * 요청자가 모달을 닫았다고 기록합니다.
     * - 서버가 사유를 다시 계산하지 않고, 화면에 보여 준 사유(shownReason)를 그대로 기록합니다.
     *   모달을 보는 사이 사유가 바뀌었다면, 기록한 사유와 지금 사유가 달라 새 사유로 모달이 한 번 더 뜹니다. (명세)
     * - 이미 담당자 없음이 아니게 됐으면(보는 사이 대상이 수락 등) 아무것도 기록하지 않고 성공으로 끝냅니다. (명세)
     * - 요청 행을 잠그고 처리합니다. 같은 지정에 수락(응답)이 동시에 들어오면, 수락이 지운 닫기 기록을 다시 쓰는 일이 없도록 차례로 처리합니다.
     */
    @Transactional
    public void dismiss(Long requestId, Long userId, NoAssigneeReason shownReason) {
        Objects.requireNonNull(shownReason, "shownReason은 필수입니다.");

        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각 (#70 리뷰와 같은 규칙)

        /**
         * 관련 없는 사람은 위 Finder에서 이미 404.
         * 여기까지 온 대상자·담당자는 요청을 볼 수 있으므로 403 (API 공통 규칙 2)
         */
        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 요청자만 닫을 수 있음
        }

        CareRequest request = access.request();
        if (request.getStatus() != CareRequestStatus.REQUESTED) {
            return; // 수락됐거나 끝난 요청 → 담당자 없음이 아님
        }
        assignmentRepository.findFirstByCareRequestIdOrderByAssignmentOrderDesc(request.getId())
                .filter(latest -> latest.currentNoAssigneeReason(now) != null) // 지금도 담당자 없음일 때만
                .ifPresent(latest -> latest.closeNotice(shownReason, now));     // 변경 감지로 저장됨
    }
}
