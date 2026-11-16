package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.*;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpRecordQueryServiceTest {
    private final CareRequestSummaryReader reader = mock(CareRequestSummaryReader.class);
    private final HelpRecordQueryService service = new HelpRecordQueryService(reader);

    @ParameterizedTest
    @CsvSource(value = {"ALL,-", "GIVEN,-", "RECEIVED,-", "ALL,20", "GIVEN,20", "RECEIVED,20",
            "ALL,10", "GIVEN,10", "RECEIVED,10"}, nullValues = "-")
    void forwardsEveryFilterAndPersonCombinationWithoutInventingSort(String type, Long personId) {
        var pageable = PageRequest.of(2, 3);
        when(reader.getHelpRecords(10L, HelpRecordFilter.valueOf(type), personId, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        var response = service.list(10L, type, personId, 2, 3);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        verify(reader).getHelpRecords(10L, HelpRecordFilter.valueOf(type), personId, pageable);
        verifyNoMoreInteractions(reader);
    }

    @Test void omittedTypeMeansAll() {
        var page = PageRequest.of(0, 20);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));
        service.list(10L, null, null, 0, 20);
        verify(reader).getHelpRecords(10L, HelpRecordFilter.ALL, null, page);
    }

    @Test void preservesCOrderRoleActualAssigneeTerminalStatusAndNullableUrgentFields() {
        var at = LocalDateTime.of(2026, 11, 16, 12, 30, 0, 123456000);
        var first = new HelpRecord(99L, 7L, HelpType.HOSPITAL, at, 20L, 10L,
                RequestDisplayStatus.CANCELED, HelpRecordFilter.GIVEN);
        var second = new HelpRecord(5L, null, HelpType.OTHER, null, 10L, null,
                RequestDisplayStatus.EXPIRED, HelpRecordFilter.RECEIVED);
        var page = PageRequest.of(0, 2);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, page))
                .thenReturn(new PageImpl<>(List.of(first, second), page, 3));
        var result = service.list(10L, "ALL", null, 0, 2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.items()).extracting(i -> i.requestId()).containsExactly(99L, 5L);
        assertThat(result.items().getFirst().startsAt()).isEqualTo(at);
        assertThat(result.items().getFirst().assigneeId()).isEqualTo(10L);
        assertThat(result.items().getFirst().role()).isEqualTo(HelpRecordFilter.GIVEN);
        assertThat(result.items().getFirst().status()).isEqualTo(RequestDisplayStatus.CANCELED);
        assertThat(result.items().get(1).situationId()).isNull();
        assertThat(result.items().get(1).startsAt()).isNull();
        assertThat(result.items().get(1).assigneeId()).isNull();
        assertThat(result.items().get(1).role()).isEqualTo(HelpRecordFilter.RECEIVED);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "given", "UNKNOWN", " ALL"})
    void invalidFilterFailsBeforeC(String type) { invalid(type, null, 0, 20); }

    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,-1"})
    void rejectsInvalidPagination(int page, int size) { invalid("ALL", null, page, size); }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void rejectsInvalidPersonId(Long personId) { invalid("ALL", personId, 0, 20); }

    @Test void rejectsMissingAuthenticatedIdBeforeC() {
        assertThatThrownBy(() -> service.list(null, null, null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));
        verifyNoInteractions(reader);
    }

    @Test void cFailureIsNotReportedAsEmptyHistory() {
        var failure = new IllegalStateException("reader failure");
        when(reader.getHelpRecords(anyLong(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.list(10L, "ALL", null, 0, 20)).isSameAs(failure);
    }

    @Test void missingCPageIsNotReportedAsEmptyHistory() {
        assertThatThrownBy(() -> service.list(10L, "ALL", null, 0, 20))
                .isInstanceOf(NullPointerException.class);
    }

    private void invalid(String type, Long personId, int page, int size) {
        assertThatThrownBy(() -> service.list(10L, type, personId, page, size))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(reader);
    }
}
