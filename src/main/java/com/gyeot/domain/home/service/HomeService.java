package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.home.dto.response.HomeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {
    private final CareRequestSummaryReader requestReader;
    private final SharingCountReader sharingCounts;
    private final Clock clock;

    public HomeResponse getHome(Long userId) {
        Objects.requireNonNull(userId, "로그인 사용자 ID는 필수입니다.");
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var overview = Objects.requireNonNull(requestReader.getHomeOverview(userId, now),
                "C 홈 조회 결과는 null일 수 없습니다.");
        long activeSharings = sharingCounts.countActiveSharings(userId);
        return HomeResponse.from(overview, activeSharings);
    }
}
