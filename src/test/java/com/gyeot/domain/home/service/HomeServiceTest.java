package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HomeRequestOverview;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.UpcomingSchedule;
import com.gyeot.domain.carerequest.enums.HelpType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HomeServiceTest {
    private ObjectProvider<CareRequestSummaryReader> provider;
    private CareRequestSummaryReader requests;
    private SharingCountReader sharings;
    private HomeService service;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 0, 0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = mock(ObjectProvider.class);
        requests = mock(CareRequestSummaryReader.class);
        sharings = mock(SharingCountReader.class);
        when(provider.getIfAvailable()).thenReturn(requests);
        service = new HomeService(provider, sharings,
                Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 동일한_로그인_ID와_서울_현재시각을_전달하고_보고된_건수를_매핑한다() {
        when(requests.getHomeOverview(10L, NOW)).thenReturn(new HomeRequestOverview(3, 4, 9,
                List.of(), List.of(), List.of()));
        when(sharings.countActiveSharings(10L)).thenReturn(7L);
        var result = service.getHome(10L);
        assertThat(result.counts().responseNeeded()).isEqualTo(3);
        assertThat(result.counts().unassigned()).isEqualTo(4);
        assertThat(result.counts().upcomingSchedules()).isEqualTo(9);
        assertThat(result.counts().activeSharings()).isEqualTo(7);
        verify(requests).getHomeOverview(10L, NOW);
        verify(sharings).countActiveSharings(10L);
        verifyNoMoreInteractions(requests, sharings);
    }

    @Test
    void 유효한_빈_결과와_공유_0은_정상_반환한다() {
        when(requests.getHomeOverview(10L, NOW)).thenReturn(new HomeRequestOverview(0, 0, 0,
                List.of(), List.of(), List.of()));
        when(sharings.countActiveSharings(10L)).thenReturn(0L);
        var result = service.getHome(10L);
        assertThat(result.responseNeededItems()).isEmpty();
        assertThat(result.sentOpenItems()).isEmpty();
        assertThat(result.upcomingSchedules()).isEmpty();
        assertThat(result.counts()).isEqualTo(new com.gyeot.domain.home.dto.response.HomeResponse.Counts(0, 0, 0, 0));
    }

    @Test
    void 일정은_C_순서를_보존해_2개만_반환하고_전체_건수는_줄이지_않는다() {
        var first = new UpcomingSchedule(3L, HelpType.HOSPITAL, NOW.plusHours(1), null, 10L, 20L);
        var second = new UpcomingSchedule(1L, HelpType.HOSPITAL, NOW.plusHours(1), null, 10L, 30L);
        var third = new UpcomingSchedule(2L, HelpType.HOSPITAL, NOW.plusHours(2), null, 10L, 40L);
        when(requests.getHomeOverview(10L, NOW)).thenReturn(new HomeRequestOverview(0, 0, 12,
                List.of(), List.of(), List.of(first, second, third)));
        var result = service.getHome(10L);
        assertThat(result.upcomingSchedules()).containsExactly(first, second);
        assertThat(result.counts().upcomingSchedules()).isEqualTo(12);
    }

    @Test
    void C_구현이_없으면_0이나_빈_결과로_위장하지_않는다() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.getHome(10L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(requests, sharings);
    }

    @Test
    void C_실패를_전파하고_D를_호출하지_않는다() {
        var error = new IllegalStateException("C failure");
        when(requests.getHomeOverview(10L, NOW)).thenThrow(error);
        assertThatThrownBy(() -> service.getHome(10L)).isSameAs(error);
        verifyNoInteractions(sharings);
    }

    @Test
    void C_null_결과를_0으로_위장하지_않는다() {
        when(requests.getHomeOverview(10L, NOW)).thenReturn(null);
        assertThatThrownBy(() -> service.getHome(10L)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(sharings);
    }

    @Test
    void D_실패를_공유_0으로_위장하지_않는다() {
        when(requests.getHomeOverview(10L, NOW)).thenReturn(new HomeRequestOverview(0, 0, 0,
                List.of(), List.of(), List.of()));
        var error = new IllegalStateException("D failure");
        when(sharings.countActiveSharings(10L)).thenThrow(error);
        assertThatThrownBy(() -> service.getHome(10L)).isSameAs(error);
    }
}
