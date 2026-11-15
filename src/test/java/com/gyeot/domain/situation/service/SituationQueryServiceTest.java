package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationQueryServiceTest {
    private final SituationRepository repository = mock(SituationRepository.class);
    private final SituationParticipantRepository participantRepository = mock(SituationParticipantRepository.class);
    private final SituationQueryService service = new SituationQueryService(repository, participantRepository);

    @Test
    void mapsBasicFieldsAndSliceMetadataUsingFixedDescendingOrder() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 15);
        Situation situation = Situation.create(10L, "종류", "목록 이름", start, start.plusHours(2));
        ReflectionTestUtils.setField(situation, "id", 7L);
        var pageable = page(2, 3);
        when(repository.findActiveForParticipant(20L, pageable))
                .thenReturn(new SliceImpl<>(List.of(situation), pageable, true));

        var result = service.listActive(20L, 2, 3);

        assertEquals(2, result.page());
        assertEquals(3, result.size());
        assertTrue(result.hasNext());
        assertEquals(1, result.items().size());
        var item = result.items().getFirst();
        assertEquals(7L, item.situationId());
        assertEquals("목록 이름", item.name());
        assertEquals("종류", item.situationType());
        assertEquals(start, item.startAt());
        assertEquals(start.plusHours(2), item.endAt());
        assertEquals(SituationStatus.ACTIVE, item.status());
        verify(repository).findActiveForParticipant(20L, pageable);
    }

    @Test
    void returnsEmptySliceWithoutExternalReaders() {
        when(repository.findActiveForParticipant(20L, page(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(), page(0, 20), false));
        var result = service.listActive(20L, 0, 20);
        assertTrue(result.items().isEmpty());
        assertFalse(result.hasNext());
        assertEquals(0, result.page());
        assertEquals(20, result.size());
    }

    @Test
    void rejectsNegativePageBeforeQuerying() {
        assertInvalid(-1, 20);
    }

    @Test
    void rejectsNonPositiveSizeBeforeQuerying() {
        assertInvalid(0, 0);
        assertInvalid(0, -1);
    }

    private void assertInvalid(int page, int size) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.listActive(20L, page, size));
        assertEquals(CommonErrorCode.INVALID_INPUT, error.getErrorCode());
        verifyNoInteractions(repository);
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
