package com.gyeot.domain.access.query;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.service.SharingCardReader;
import com.gyeot.domain.access.service.SharingCardView;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공유 중 건수가 공유 중 카드(findSharingCards) 수와 같은지 확인합니다.
 * 카드 판단과 철회됨 카드 제외 자체는 SharingCardReaderTest에서 확인합니다.
 */
class SharingCountReaderImplTest {

    private static final Long OWNER_ID = 10L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 2, 14, 0);

    private SharingCardReader sharingCardReader;
    private SharingCountReaderImpl countReader;

    @BeforeEach
    void setUp() {
        sharingCardReader = mock(SharingCardReader.class);
        countReader = new SharingCountReaderImpl(sharingCardReader);
    }

    @Test
    void 공유_중_카드_수를_센다() {
        // 받는 사람으로 거르지 않은 전체 카드(null)로 호출돼야 함 (다른 인자면 Mock이 빈 목록을 돌려 0이 됨)
        when(sharingCardReader.findSharingCards(OWNER_ID, null)).thenReturn(List.of(
                card(40L),
                card(41L)));

        assertThat(countReader.countActiveSharings(OWNER_ID)).isEqualTo(2);
    }

    @Test
    void 카드가_없으면_0이다() {
        when(sharingCardReader.findSharingCards(OWNER_ID, null)).thenReturn(List.of());

        assertThat(countReader.countActiveSharings(OWNER_ID)).isZero();
    }

    private static SharingCardView card(Long assignmentId) {
        return new SharingCardView(assignmentId, 31L, 20L, 5L, HelpType.HOSPITAL, STARTS_AT, STARTS_AT.plusHours(3),
                SharingStatus.SHARING, false, List.of(SharedItemName.of(InformationType.MEDICATION, "혈압약")));
    }
}
