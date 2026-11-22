package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecord;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecordFilter;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.situation.service.SituationHistoryService.Filter;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationHistoryServiceTest {
    private SituationHistoryQueryRepository repository;
    private ObjectProvider<CareRequestSummaryReader> provider;
    private CareRequestSummaryReader reader;
    private SituationHistoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(SituationHistoryQueryRepository.class);
        provider = mock(ObjectProvider.class);
        reader = mock(CareRequestSummaryReader.class);
        service = new SituationHistoryService(repository, provider, ids -> java.util.Map.of());
    }

    @Test
    void 목록은_필드와_페이지와_hasNext를_매핑하고_정렬을_고정한다() {
        var situation = ended();
        var pageable = PageRequest.of(2, 20, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        when(repository.findHistory(10L, pageable)).thenReturn(new SliceImpl<>(List.of(situation), pageable, true));
        var result = service.list(10L, 2, 20, Filter.ALL, null);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isTrue();
        var item = result.items().getFirst();
        assertThat(item.situationId()).isEqualTo(1L);
        assertThat(item.name()).isEqualTo("지난 돌봄");
        assertThat(item.situationType()).isEqualTo("돌봄");
        assertThat(item.startAt()).isEqualTo(situation.getStartAt());
        assertThat(item.endAt()).isEqualTo(situation.getEndAt());
        assertThat(item.endedAt()).isEqualTo(situation.getEndedAt());
        verifyNoInteractions(provider, reader);
    }

    @Test
    void 빈_목록은_정상_반환한다() {
        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        when(repository.findHistory(10L, pageable)).thenReturn(new SliceImpl<>(List.of(), pageable, false));
        var result = service.list(10L, 0, 20, Filter.ALL, null);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void 잘못된_페이지와_크기는_400이다() {
        for (int[] input : List.of(new int[]{-1, 20}, new int[]{0, -1}, new int[]{0, 0}, new int[]{0, 101})) {
            assertThatThrownBy(() -> service.list(10L, input[0], input[1], Filter.ALL, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(400));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void 사람별_전체는_양쪽_역할의_완료_기록만_상황별로_모아_페이징한다() {
        when(provider.getIfAvailable()).thenReturn(reader);
        var records = baseRecords();
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(records));
        var pageable = PageRequest.of(0, 1, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        when(repository.findFilteredHistory(10L, java.util.Set.of(1L, 2L), pageable))
                .thenReturn(new SliceImpl<>(List.of(ended()), pageable, true));

        var result = service.list(10L, 0, 1, Filter.ALL, 20L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.items()).extracting(item -> item.situationId()).containsExactly(1L);
        verify(repository).findFilteredHistory(10L, java.util.Set.of(1L, 2L), pageable);

        when(repository.findFilteredHistory(10L, java.util.Set.of(1L), pageable))
                .thenReturn(new SliceImpl<>(List.of(ended()), pageable, false));
        assertThat(service.list(10L, 0, 1, Filter.HELPED, 20L).hasNext()).isFalse();
    }

    @Test
    void 후보는_접근_가능한_상황에서만_수집하고_탈퇴_이름을_표시한다() {
        when(provider.getIfAvailable()).thenReturn(reader);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(baseRecords()));
        when(repository.findAccessibleHistoryIds(10L, java.util.Set.of(1L, 2L, 4L)))
                .thenReturn(List.of(1L, 2L));
        var peopleService = new SituationHistoryService(repository, provider,
                ids -> java.util.Map.of(20L, new UserProfileQuery.UserProfile(20L, null, true)));

        var result = peopleService.people(10L, Filter.ALL);
        assertThat(result.items()).extracting(item -> item.userId(), item -> item.name())
                .containsExactly(tuple(20L, "탈퇴한 사용자"));
    }

    private List<HelpRecord> baseRecords() {
        return List.of(
                record(1L, 1L, 20L, 10L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.GIVEN),
                record(2L, 2L, 10L, 20L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.RECEIVED),
                record(3L, 1L, 20L, 10L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.GIVEN),
                record(4L, 3L, 20L, 10L, RequestDisplayStatus.CANCELED, HelpRecordFilter.GIVEN),
                record(5L, null, 20L, 10L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.GIVEN),
                record(6L, 4L, 30L, 10L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.GIVEN));
    }

    private HelpRecord record(Long id, Long situationId, Long requesterId, Long assigneeId,
                              RequestDisplayStatus status, HelpRecordFilter role) {
        return new HelpRecord(id, situationId, com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL,
                null, requesterId, assigneeId, status, role);
    }

    @Test
    void 상세_접근_불가이면_C를_조회하지_않고_404이다() {
        when(repository.findAccessibleHistory(1L, 20L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(1L, 20L)).isInstanceOfSatisfying(BusinessException.class,
                e -> {
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(404);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("SITUATION_NOT_FOUND");
                });
        verifyNoInteractions(provider, reader);
    }

    @Test
    void 권한_검증_후에만_C를_호출하고_기본_정보를_매핑한다() {
        var situation = ended();
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(situation));
        when(provider.getIfAvailable()).thenReturn(reader);
        when(reader.getPastRequestRecords(1L, 10L)).thenReturn(List.of());
        var result = service.detail(1L, 10L);
        assertThat(result.situation().situationId()).isEqualTo(1L);
        assertThat(result.situation().name()).isEqualTo("지난 돌봄");
        assertThat(result.situation().endedAt()).isEqualTo(situation.getEndedAt());
        assertThat(result.situation().startAt()).isEqualTo(situation.getStartAt());
        assertThat(result.situation().endAt()).isEqualTo(situation.getEndAt());
        var order = inOrder(repository, provider, reader);
        order.verify(repository).findAccessibleHistory(1L, 10L);
        order.verify(provider).getIfAvailable();
        order.verify(reader).getPastRequestRecords(1L, 10L);
    }

    @Test
    void C_구현이_없으면_성공으로_위장하지_않는다() {
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(ended()));
        assertThatThrownBy(() -> service.detail(1L, 10L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void C_요청_기록의_예정_시작과_종료_시각을_매핑한다() {
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(ended()));
        when(provider.getIfAvailable()).thenReturn(reader);
        var scheduledAt = LocalDateTime.of(2026, 10, 10, 14, 0);
        when(reader.getPastRequestRecords(1L, 10L)).thenReturn(List.of(
                new CareRequestSummaryReader.PastRequestRecord(9L,
                        com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, scheduledAt, 10L, 20L,
                        com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED, scheduledAt.plusHours(1))));
        var record = service.detail(1L, 10L).requests().getFirst();
        assertThat(record.requestId()).isEqualTo(9L);
        assertThat(record.startsAt()).isEqualTo(scheduledAt);
        assertThat(record.endsAt()).isEqualTo(scheduledAt.plusHours(1));
        assertThat(record.assigneeId()).isEqualTo(20L);
        assertThat(record.helpType()).isEqualTo(com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL);
        assertThat(record.status()).isEqualTo(com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED);
    }

    @Test
    void C_실패를_빈_목록으로_바꾸지_않는다() {
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(ended()));
        when(provider.getIfAvailable()).thenReturn(reader);
        var failure = new IllegalStateException("test failure");
        when(reader.getPastRequestRecords(1L, 10L)).thenThrow(failure);
        assertThatThrownBy(() -> service.detail(1L, 10L)).isSameAs(failure);
    }

    @Test
    void C_null_결과도_빈_목록으로_바꾸지_않는다() {
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(ended()));
        when(provider.getIfAvailable()).thenReturn(reader);
        when(reader.getPastRequestRecords(1L, 10L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(1L, 10L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 참여자_조회도_실제_조회자_ID를_C에_전달하고_C의_결과_순서를_유지한다() {
        when(repository.findAccessibleHistory(1L, 20L)).thenReturn(Optional.of(ended()));
        when(provider.getIfAvailable()).thenReturn(reader);
        var start = LocalDateTime.of(2026, 10, 10, 14, 0, 0, 123456000);
        var records = List.of(
                new CareRequestSummaryReader.PastRequestRecord(91L,
                        com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, start, 10L, 20L,
                        com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED),
                new CareRequestSummaryReader.PastRequestRecord(92L,
                        com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, start.plusMinutes(1), 10L, 20L,
                        com.gyeot.domain.carerequest.query.RequestDisplayStatus.CANCELED),
                new CareRequestSummaryReader.PastRequestRecord(93L,
                        com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, null, 20L, null,
                        com.gyeot.domain.carerequest.query.RequestDisplayStatus.EXPIRED));
        when(reader.getPastRequestRecords(1L, 20L)).thenReturn(records);

        var result = service.detail(1L, 20L);

        assertThat(result.requests()).extracting(r -> r.requestId()).containsExactly(91L, 92L, 93L);
        assertThat(result.requests()).extracting(r -> r.status()).containsExactly(
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.CANCELED,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.EXPIRED);
        assertThat(result.requests().getFirst().startsAt()).isEqualTo(start);
        assertThat(result.requests().getLast().startsAt()).isNull();
        assertThat(result.requests().getLast().assigneeId()).isNull();
        verify(reader).getPastRequestRecords(1L, 20L);
        verifyNoMoreInteractions(reader);
    }

    private Situation ended() {
        Situation situation = Situation.create(10L, "돌봄", "지난 돌봄",
                LocalDateTime.of(2026, 10, 1, 9, 15, 30, 123456000), LocalDateTime.of(2026, 10, 31, 18, 45, 30, 654321000));
        ReflectionTestUtils.setField(situation, "id", 1L);
        situation.end(LocalDateTime.of(2026, 11, 1, 0, 0));
        return situation;
    }
}
