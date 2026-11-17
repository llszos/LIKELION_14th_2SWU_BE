package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.ReceivedSharingResponse.ReceivedSharing;
import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 받은 정보 조회가 카드 판단 결과를 응답으로 바꾸는지 확인합니다. (볼 수 있는 카드 판단 자체는 SharingCardReaderTest)
 */
class ReceivedSharingServiceTest {

    private static final Long VIEWER_ID = 20L; // 로그인한 나 (받는 사람)
    private static final Long OWNER_ID = 10L;  // 화면의 '이 사람' (정보 주인)
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 8, 10, 0);

    private SharingCardReader sharingCardReader;
    private ReceivedSharingService service;

    @BeforeEach
    void setUp() {
        sharingCardReader = mock(SharingCardReader.class);
        service = new ReceivedSharingService(sharingCardReader);
    }

    @Test
    void 정보_주인과_받는_사람을_바꿔_볼_수_있는_카드를_조회하고_요청_단위로_내려준다() {
        // 볼 수 있는 카드 판단은 정보 주인 = 이 사람, 받는 사람 = 나로 호출돼야 함 (순서가 바뀌면 stub이 응답하지 않음)
        when(sharingCardReader.findViewableCards(OWNER_ID, VIEWER_ID)).thenReturn(List.of(
                card(40L, 31L, STARTS_AT.plusHours(8), SharedItemName.of(InformationType.ALLERGY, "페니실린")),
                card(41L, 32L, STARTS_AT.plusHours(9), SharedItemName.of(InformationType.MEDICATION, "혈압약"))));

        ReceivedSharingResponse response = service.getReceived(VIEWER_ID, OWNER_ID);

        // 카드 순서(자동 회수 시각 빠른 순)를 그대로 유지
        assertThat(response.items())
                .extracting(ReceivedSharing::requestId, ReceivedSharing::helpType, ReceivedSharing::accessUntil)
                .containsExactly(
                        tuple(31L, HelpType.HOSPITAL, STARTS_AT.plusHours(8)),
                        tuple(32L, HelpType.HOSPITAL, STARTS_AT.plusHours(9)));
        assertThat(response.items().get(0).sharedItems())
                .containsExactly(SharedItemName.of(InformationType.ALLERGY, "페니실린"));
    }

    @Test
    void 받은_공유가_없으면_빈_목록이다() {
        when(sharingCardReader.findViewableCards(OWNER_ID, VIEWER_ID)).thenReturn(List.of());

        assertThat(service.getReceived(VIEWER_ID, OWNER_ID).items()).isEmpty();
    }

    /** 볼 수 있는 카드 (findViewableCards는 공유 중 카드만 돌려줌) */
    private static SharingCardView card(Long assignmentId, Long requestId, LocalDateTime accessUntil, SharedItemName... items) {
        return new SharingCardView(assignmentId, requestId, VIEWER_ID, 5L, HelpType.HOSPITAL, STARTS_AT, accessUntil,
                SharingStatus.SHARING, false, List.of(items));
    }
}
