package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 요청 요약 조회 인터페이스 (C → B)
 * B 영역(상황·홈·지난 상황) 화면에 필요한 요청 정보를 제공합니다.
 * - 사용자 이름·프로필은 담지 않고 사용자 ID만 담습니다. 이름은 B가 A에서 조회합니다.
 * - 민감정보(안심카드)는 담지 않습니다. 요청 장소는 응답이 필요한 요청 목록에만 담습니다(대상자에게 이미 보이는 정보).
 * - 담당자 없음·응답 대기 판단은 RequestDisplayStatus 한 곳에서 계산합니다.
 * - now를 받는 메서드는 응답 기한 경과 여부를 계산합니다. 테스트에서 시각을 고정할 수 있도록 인자로 받습니다.
 *
 * 구현은 CareRequestSummaryReaderImpl입니다. (#67)
 * - 기록상 담당자: 지난 상황 기록·도움 기록의 "담당자"는 요청이 끝날 때 담당자였던 사람입니다.
 * - 수락했다가 시작 전에 철회한 사람은 담당자로 보지 않습니다. (RecordAssigneeRule)
 */
public interface CareRequestSummaryReader {

    // === S-00 상황 탭, H-00 홈: 진행 중 상황 카드 ===

    /**
     * 상황별 요청 현황 (여러 상황을 한 번에 조회)
     * 요청이 없는 상황도 0건 통계로 채워서 돌려줍니다.
     */
    Map<Long, SituationRequestStats> getSituationStats(Collection<Long> situationIds, LocalDateTime now);

    // === H-03 상황 상세 ===

    /**
     * 상황 안의 요청 목록 (시작 시각 순)
     * - 요청자(정보 주인)가 보면 전체
     * - 그 외 참여자가 보면 본인이 대상자이거나 담당자인 요청 + 끝난 요청 중 본인이 기록상 담당자인 요청
     *   (다른 사람에게 넘어간 이전 대상, 수락 철회한 사람의 요청은 목록에서 빠짐. 상세는 계속 볼 수 있음)
     */
    List<RequestSummary> getSituationRequests(Long situationId, Long viewerId, LocalDateTime now);

    // === H-00 홈 ===

    /**
     * 홈의 요청 관련 영역 전체 (한눈에 보기 건수, 응답이 필요한 요청, 내가 보낸 요청, 다가오는 일정)
     * 응답이 필요한 요청 (F-06, F-11)
     * - RESPONSE: 내가 지금 대상자이고, 미응답 또는 확인 후 답변이며, 아직 응답할 수 있는 요청
     *   (일반 요청은 응답 기한이 지나도 시작 전이면 응답 가능하므로 시작 전까지 포함, F-06)
     * - RECONFIRMATION: 내가 담당자인 요청의 대기 중 수정안 중 재확인 기한이 지나지 않은 것
     * - 정렬: 기한이 빠른 순
     */
    HomeRequestOverview getHomeOverview(Long userId, LocalDateTime now);

    // === H-04 지난 상황, N-04 지난 상황 상세 ===

    /** 종료된 상황별 요청 수·완료 수. 취소·만료된 요청도 요청 수에 포함합니다. (F-22) */
    Map<Long, PastSituationStats> getPastSituationStats(Collection<Long> situationIds);

    /**
     * 지난 상황의 요청별 결과 (시작 시각 순)
     * - 요청자(정보 주인)가 보면 전체
     * - 그 외 참여자가 보면 본인이 기록상 담당자인 요청만 (내가 도운 기록, 나중에 취소된 요청 포함)
     */
    List<PastRequestRecord> getPastRequestRecords(Long situationId, Long viewerId);

    // === 도움 기록 (F-22, B #64 GET /api/help-records) ===

    /**
     * 사용자의 도움 기록 (끝난 요청만: 완료·취소·만료)
     * - GIVEN: 내가 기록상 담당자인 요청 (내가 도와준 기록)
     * - RECEIVED: 내가 요청자인 요청 (내가 도움받은 기록, 담당자 없이 만료된 요청 포함)
     * - ALL: 둘 다
     * - personId: 상대방 필터. GIVEN이면 요청자, RECEIVED면 기록상 담당자가 이 사람인 것만. null이면 전체
     * - 정렬은 C가 정했습니다: 시작 일시 최신순, 같으면 요청 ID 최신순.
     *   pageable의 페이지 번호·크기만 쓰고 정렬(sort)은 무시합니다. (B가 C 엔티티 필드 이름을 알 필요가 없도록)
     */
    Page<HelpRecord> getHelpRecords(Long userId, HelpRecordFilter filter, Long personId, Pageable pageable);

    // === 반환 타입 ===

    /**
     * 상황 카드 통계
     * nextSchedule: 수락됨·진행 중 요청 중 시작이 가장 빠른 것. 없으면 null
     */
    record SituationRequestStats(
            int acceptedCount,
            int inProgressCount,
            int waitingCount,
            int noAssigneeCount,
            UpcomingSchedule nextSchedule
    ) {
        public static SituationRequestStats empty() {
            return new SituationRequestStats(0, 0, 0, 0, null);
        }
    }

    /**
     * 요청 목록의 한 줄
     * targetUserId: 가장 최근 지정의 대상자(수락했으면 담당자). 지정이 없으면 null
     */
    record RequestSummary(
            Long requestId,
            RequestType requestType,
            HelpType helpType,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Long requesterId,
            Long targetUserId,
            RequestDisplayStatus displayStatus
    ) {
    }

    /** 다가오는 일정 한 줄 (수락됨·진행 중) */
    record UpcomingSchedule(
            Long requestId,
            HelpType helpType,
            LocalDateTime startsAt,
            String place,
            Long requesterId,
            Long assigneeId
    ) {
    }

    /**
     * 홈 요약
     * - responseNeededCount: 응답이 필요한 요청 수 (responseNeeded 전체 개수, 목록은 개수 제한 없음)
     * - noAssigneeCount: 내가 보낸 요청 중 담당자 없음 수
     * - upcomingCount: 내가 부탁했거나 맡은 수락됨·진행 중 요청 수
     * - sentRequests: 내가 보낸 끝나지 않은 요청 (요청됨·수락됨·진행 중)
     * - upcoming: 다가오는 일정, 시작이 빠른 순 최대 2개
     */
    record HomeRequestOverview(
            int responseNeededCount,
            int noAssigneeCount,
            int upcomingCount,
            List<ResponseNeededItem> responseNeeded,
            List<RequestSummary> sentRequests,
            List<UpcomingSchedule> upcoming
    ) {
        public HomeRequestOverview {
            responseNeeded = List.copyOf(responseNeeded);
            sentRequests = List.copyOf(sentRequests);
            upcoming = List.copyOf(upcoming);
        }
    }

    /**
     * 응답이 필요한 요청 한 줄
     * - RESPONSE: 새로 받은 요청(대상이 바뀌어 받은 요청 포함), deadline은 응답 기한
     * - RECONFIRMATION: 수정 후 재확인이 필요한 요청, deadline은 재확인 기한
     */
    record ResponseNeededItem(
            Long requestId,
            ResponseNeededType type,
            RequestType requestType,
            HelpType helpType,
            LocalDateTime startsAt,
            String place,
            Long requesterId,
            LocalDateTime deadline
    ) {
    }

    enum ResponseNeededType {
        RESPONSE,
        RECONFIRMATION
    }

    /** 지난 상황 통계 */
    record PastSituationStats(int requestCount, int completedCount) {
        public static PastSituationStats empty() {
            return new PastSituationStats(0, 0);
        }
    }

    /**
     * 지난 상황의 요청 기록 한 줄 (민감정보 없이 도움 종류·담당자·날짜·상태만, F-22)
     * - assigneeId: 수락했던 담당자. 수락한 사람이 없었으면 null
     * - status: COMPLETED·CANCELED·EXPIRED. 감사 메시지(F-21) 버튼은 COMPLETED일 때만
     */
    record PastRequestRecord(
            Long requestId,
            HelpType helpType,
            LocalDateTime startsAt,
            Long requesterId,
            Long assigneeId,
            RequestDisplayStatus status
    ) {
    }

    /** 도움 기록 필터, 그리고 기록 한 줄에서 나의 역할 */
    enum HelpRecordFilter {
        ALL,      // 전체 (필터로만 사용)
        GIVEN,    // 내가 도와준 기록
        RECEIVED  // 내가 도움받은 기록
    }

    /**
     * 도움 기록 한 줄 (민감정보 없이 도움 종류·담당자·날짜·상태만, F-22)
     * - situationId, startsAt: 급한 요청(F-13)은 상황·일정이 없어 null
     * - assigneeId: 기록상 담당자. 담당자 없이 끝났으면(만료 등) null
     * - status: COMPLETED·CANCELED·EXPIRED
     * - role: 이 기록에서 나의 역할 (GIVEN 또는 RECEIVED). ALL로 조회했을 때 화면 표시에 사용
     */
    record HelpRecord(
            Long requestId,
            Long situationId,
            HelpType helpType,
            LocalDateTime startsAt,
            Long requesterId,
            Long assigneeId,
            RequestDisplayStatus status,
            HelpRecordFilter role
    ) {
    }
}