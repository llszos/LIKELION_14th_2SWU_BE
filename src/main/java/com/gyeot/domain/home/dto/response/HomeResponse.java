package com.gyeot.domain.home.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HomeRequestOverview;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededItem;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.RequestSummary;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.UpcomingSchedule;

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
                overview.upcomingCount(), activeSharings), overview.responseNeeded(), overview.sentRequests(),
                overview.upcoming().stream().limit(2).toList());
    }

    /** upcomingSchedules는 표시 목록 크기가 아닌 C가 제공하는 전체 예정 일정 수입니다. */
    public record Counts(int responseNeeded, int unassigned, int upcomingSchedules, long activeSharings) {}
}
