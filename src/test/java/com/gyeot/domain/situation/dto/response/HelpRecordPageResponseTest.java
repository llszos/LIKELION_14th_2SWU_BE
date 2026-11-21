package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.*;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HelpRecordPageResponseTest {
    @Test void responseTakesImmutableSnapshotOfItems() {
        var item = new HelpRecordPageResponse.Item(1L, null, HelpType.OTHER, null, 10L, null,
                RequestDisplayStatus.EXPIRED, HelpRecordFilter.RECEIVED);
        var source = new ArrayList<>(List.of(item));
        var response = new HelpRecordPageResponse(source, 0, 20, false);
        source.clear();
        assertThat(response.items()).containsExactly(item);
        assertThatThrownBy(() -> response.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void noRecordsIsAnEmptySuccessfulPageAtTheRequestedOffset() {
        var result = HelpRecordPageResponse.from(new PageImpl<>(List.of(), PageRequest.of(3, 10), 0));
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.hasNext()).isFalse();
    }

    @Test void nullPageFailsRatherThanInventingZeroResults() {
        assertThatThrownBy(() -> HelpRecordPageResponse.from(null)).isInstanceOf(NullPointerException.class);
    }
}
