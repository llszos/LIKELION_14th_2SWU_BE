package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
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
        service = new SituationHistoryService(repository, provider);
    }

    @Test
    void 목록은_필드와_페이지와_hasNext를_매핑하고_정렬을_고정한다() {
        var situation = ended();
        var pageable = PageRequest.of(2, 20, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        when(repository.findHistory(10L, pageable)).thenReturn(new SliceImpl<>(List.of(situation), pageable, true));
        var result = service.list(10L, 2, 20);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isTrue();
        var item = result.items().getFirst();
        assertThat(item.situationId()).isEqualTo(1L);
        assertThat(item.name()).isEqualTo("지난 돌봄");
        assertThat(item.situationType()).isEqualTo("돌봄");
        assertThat(item.startDate()).isEqualTo(situation.getStartDate());
        assertThat(item.endDate()).isEqualTo(situation.getEndDate());
        assertThat(item.endedAt()).isEqualTo(situation.getEndedAt());
        verifyNoInteractions(provider, reader);
    }

    @Test
    void 빈_목록은_정상_반환한다() {
        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        when(repository.findHistory(10L, pageable)).thenReturn(new SliceImpl<>(List.of(), pageable, false));
        var result = service.list(10L, 0, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void 잘못된_페이지와_크기는_400이다() {
        for (int[] input : List.of(new int[]{-1, 20}, new int[]{0, -1}, new int[]{0, 0}, new int[]{0, 101})) {
            assertThatThrownBy(() -> service.list(10L, input[0], input[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(400));
        }
        verifyNoInteractions(repository);
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
    void C_요청_기록은_API_초안_필드로_매핑한다() {
        when(repository.findAccessibleHistory(1L, 10L)).thenReturn(Optional.of(ended()));
        when(provider.getIfAvailable()).thenReturn(reader);
        var scheduledAt = LocalDate.of(2026, 10, 10).atTime(14, 0);
        when(reader.getPastRequestRecords(1L, 10L)).thenReturn(List.of(
                new CareRequestSummaryReader.PastRequestRecord(9L,
                        com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, scheduledAt, 10L, 20L,
                        com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED)));
        var record = service.detail(1L, 10L).requests().getFirst();
        assertThat(record.requestId()).isEqualTo(9L);
        assertThat(record.scheduledAt()).isEqualTo(scheduledAt);
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

    private Situation ended() {
        Situation situation = Situation.create(10L, "돌봄", "지난 돌봄",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        ReflectionTestUtils.setField(situation, "id", 1L);
        situation.end(LocalDate.of(2026, 11, 1).atStartOfDay());
        return situation;
    }
}
