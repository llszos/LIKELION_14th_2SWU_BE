package com.gyeot.domain.situation.scheduler;

import com.gyeot.domain.situation.service.SituationExpirationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

import static org.mockito.Mockito.*;

class SituationExpirationSchedulerTest {
    @Test
    void 직접_호출하면_후보들을_개별_처리한다() {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(0L)).thenReturn(List.of(1L, 2L));
        when(service.expire(1L)).thenReturn(true);
        new SituationExpirationScheduler(service).expireSituations();
        verify(service).expire(1L);
        verify(service).expire(2L);
    }

    @Test
    void 개별_실패가_발생해도_나머지_대상을_처리한다() {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(0L)).thenReturn(List.of(1L, 2L, 3L));
        when(service.expire(1L)).thenThrow(new IllegalStateException("test failure"));
        new SituationExpirationScheduler(service).expireSituations();
        verify(service).expire(2L);
        verify(service).expire(3L);
    }

    @Test
    void 후보가_없으면_종료를_호출하지_않는다() {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(0L)).thenReturn(List.of());
        new SituationExpirationScheduler(service).expireSituations();
        verify(service, never()).expire(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99, 100, 101, 250})
    void 배치_경계와_마지막_작은_배치를_빠짐없이_처리한다(int count) {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(anyLong())).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(0);
            return LongStream.rangeClosed(afterId + 1, Math.min(afterId + 100, count)).boxed().toList();
        });
        new SituationExpirationScheduler(service).expireSituations();
        for (long id = 1; id <= count; id++) {
            verify(service).expire(id);
        }
        verify(service, times(count)).expire(anyLong());
        verify(service).findCandidates((long) count);
        verify(service, times((count + 99) / 100 + 1)).findCandidates(anyLong());
    }

    @Test
    void 마지막_ID가_실패해도_다음_배치로_전진하고_같은_실행에서_재조회하지_않는다() {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(0L)).thenReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        when(service.findCandidates(100L)).thenReturn(List.of(101L, 105L));
        when(service.findCandidates(105L)).thenReturn(List.of());
        when(service.expire(100L)).thenThrow(new IllegalStateException("test failure"));
        new SituationExpirationScheduler(service).expireSituations();
        verify(service).expire(100L);
        verify(service).expire(101L);
        verify(service).expire(105L);
        verify(service, times(3)).findCandidates(anyLong());
        verify(service, times(102)).expire(anyLong());
    }

    @Test
    void 실패_로그에_상황_ID와_메시지와_스택을_남긴다() {
        var service = mock(SituationExpirationService.class);
        when(service.findCandidates(0L)).thenReturn(List.of(42L));
        when(service.expire(42L)).thenThrow(new IllegalStateException("test failure"));
        Logger logger = (Logger) LoggerFactory.getLogger(SituationExpirationScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new SituationExpirationScheduler(service).expireSituations();
            var event = appender.list.stream().filter(e -> e.getThrowableProxy() != null).findFirst().orElseThrow();
            assertThat(event.getFormattedMessage()).contains("situationId=42", "test failure");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
