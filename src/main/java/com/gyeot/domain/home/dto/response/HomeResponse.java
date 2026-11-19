package com.gyeot.domain.home.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HomeRequestOverview;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededType;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** C의 사용자별 사실과 D의 실제 활성 공유 수만 제공합니다. 제목·이름·상황 카드는 생성하지 않습니다. */
public record HomeResponse(Counts counts, List<ResponseNeededItem> responseNeededItems,
                           List<RequestSummary> sentOpenItems, List<UpcomingSchedule> upcomingSchedules) {
    public HomeResponse {
        Objects.requireNonNull(counts, "홈 건수는 필수입니다.");
        responseNeededItems = List.copyOf(responseNeededItems);
        sentOpenItems = List.copyOf(sentOpenItems);
        upcomingSchedules = List.copyOf(upcomingSchedules);
    }

    public static HomeResponse from(HomeRequestOverview overview, long activeSharings) {
        Objects.requireNonNull(overview, "C 홈 조회 결과는 필수입니다.");
        return new HomeResponse(new Counts(overview.responseNeededCount(), overview.noAssigneeCount(),
                overview.upcomingCount(), activeSharings),
                overview.responseNeeded().stream().map(ResponseNeededItem::from).toList(),
                overview.sentRequests().stream().map(RequestSummary::from).toList(),
                // C 계약도 최대 2개이지만 기존 HTTP 응답의 크기 보장을 방어적으로 유지합니다.
                overview.upcoming().stream().limit(2).map(UpcomingSchedule::from).toList());
    }

    /** upcomingSchedules는 표시 목록 크기가 아닌 C가 제공하는 전체 예정 일정 수입니다. */
    public record Counts(int responseNeeded, int unassigned, int upcomingSchedules, long activeSharings) {}

    public record ResponseNeededItem(Long requestId, ResponseNeededType type, RequestType requestType,
                                     HelpType helpType, LocalDateTime startsAt, String place,
                                     Long requesterId, LocalDateTime deadline) {
        private static ResponseNeededItem from(CareRequestSummaryReader.ResponseNeededItem item) {
            return new ResponseNeededItem(item.requestId(), item.type(), item.requestType(), item.helpType(),
                    item.startsAt(), item.place(), item.requesterId(), item.deadline());
        }
    }

    public record RequestSummary(Long requestId, RequestType requestType, HelpType helpType,
                                 LocalDateTime startsAt, LocalDateTime endsAt, Long requesterId,
                                 Long targetUserId, RequestDisplayStatus displayStatus) {
        private static RequestSummary from(CareRequestSummaryReader.RequestSummary item) {
            return new RequestSummary(item.requestId(), item.requestType(), item.helpType(), item.startsAt(),
                    item.endsAt(), item.requesterId(), item.targetUserId(), item.displayStatus());
        }
    }

    public record UpcomingSchedule(Long requestId, HelpType helpType, LocalDateTime startsAt,
                                   String place, Long requesterId, Long assigneeId) {
        private static UpcomingSchedule from(CareRequestSummaryReader.UpcomingSchedule item) {
            return new UpcomingSchedule(item.requestId(), item.helpType(), item.startsAt(), item.place(),
                    item.requesterId(), item.assigneeId());
        }
    }
}
