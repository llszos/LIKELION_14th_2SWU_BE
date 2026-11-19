package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingListResponse;
import com.gyeot.domain.access.dto.response.SharingListResponse.Receiver;
import com.gyeot.domain.access.dto.response.SharingListResponse.SharingCard;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 중 목록 조회가 카드 판단 결과에 받는 사람 이름을 채워 응답으로 바꾸는지 확인합니다.
 * (카드 판단 자체는 SharingCardReaderTest, 사용자 프로필 조회는 A의 UserProfileQuery 테스트)
 */
class SharingListServiceTest {

    private static final Long OWNER_ID = 10L;    // 로그인한 나 (정보 주인)
    private static final Long MINJAE_ID = 20L;   // 받는 사람 1
    private static final Long HAEUN_ID = 21L;    // 받는 사람 2
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 2, 14, 0);

    private SharingCardReader sharingCardReader;
    private UserProfileQuery userProfileQuery;
    private SharingListService service;

    @BeforeEach
    void setUp() {
        sharingCardReader = mock(SharingCardReader.class);
        userProfileQuery = mock(UserProfileQuery.class);
        service = new SharingListService(sharingCardReader, userProfileQuery);
    }

    @Test
    void 카드_판단_결과를_순서대로_내려주고_받는_사람_이름을_채운다() {
        SharingCardView sharing = card(40L, 31L, MINJAE_ID, STARTS_AT.plusHours(4), SharingStatus.SHARING, false,
                SharedItemName.of(InformationType.MEDICATION, "혈압약"));
        SharingCardView revoked = card(52L, 38L, HAEUN_ID, STARTS_AT.plusDays(3), SharingStatus.REVOKED, true,
                SharedItemName.of(InformationType.PET, "나롱이"));
        when(sharingCardReader.findCards(OWNER_ID, null)).thenReturn(List.of(sharing, revoked));
        when(userProfileQuery.findProfilesByUserIds(List.of(MINJAE_ID, HAEUN_ID))).thenReturn(Map.of(
                MINJAE_ID, new UserProfile(MINJAE_ID, "민재", false),
                HAEUN_ID, new UserProfile(HAEUN_ID, "하은", false)));

        SharingListResponse response = service.getSharings(OWNER_ID, null);

        // 카드 순서(자동 회수 시각 빠른 순)와 카드 값을 그대로 옮기고, 받는 사람에 이름만 붙임
        assertThat(response.items())
                .extracting(SharingCard::assignmentId, SharingCard::requestId, SharingCard::receiver,
                        SharingCard::status, SharingCard::canReallow, SharingCard::accessUntil)
                .containsExactly(
                        tuple(40L, 31L, new Receiver(MINJAE_ID, "민재"), SharingStatus.SHARING, false, STARTS_AT.plusHours(4)),
                        tuple(52L, 38L, new Receiver(HAEUN_ID, "하은"), SharingStatus.REVOKED, true, STARTS_AT.plusDays(3)));
        assertThat(response.items().get(0).helpType()).isEqualTo(HelpType.HOSPITAL);
        assertThat(response.items().get(0).startsAt()).isEqualTo(STARTS_AT);
        assertThat(response.items().get(1).sharedItems()).containsExactly(SharedItemName.of(InformationType.PET, "나롱이"));
    }

    @Test
    void 받는_사람_ID를_넘기면_그_사람_기준으로_카드를_판단한다() {
        when(sharingCardReader.findCards(OWNER_ID, MINJAE_ID)).thenReturn(List.of(
                card(40L, 31L, MINJAE_ID, STARTS_AT.plusHours(4), SharingStatus.SHARING, false,
                        SharedItemName.of(InformationType.ALLERGY, "페니실린"))));
        when(userProfileQuery.findProfilesByUserIds(List.of(MINJAE_ID)))
                .thenReturn(Map.of(MINJAE_ID, new UserProfile(MINJAE_ID, "민재", false)));

        // R-02-01: 받는 사람 ID가 카드 판단에 그대로 넘어가야 함 (다르면 stub이 응답하지 않아 빈 목록)
        assertThat(service.getSharings(OWNER_ID, MINJAE_ID).items())
                .extracting(SharingCard::assignmentId)
                .containsExactly(40L);
    }

    @Test
    void 같은_사람의_카드가_여러_개여도_이름은_한_번에_조회한다() {
        when(sharingCardReader.findCards(OWNER_ID, null)).thenReturn(List.of(
                card(40L, 31L, MINJAE_ID, STARTS_AT.plusHours(4), SharingStatus.SHARING, false,
                        SharedItemName.of(InformationType.MEDICATION, "혈압약")),
                card(41L, 32L, MINJAE_ID, STARTS_AT.plusHours(6), SharingStatus.SHARING, false,
                        SharedItemName.of(InformationType.MEDICATION, "당뇨약"))));
        when(userProfileQuery.findProfilesByUserIds(any()))
                .thenReturn(Map.of(MINJAE_ID, new UserProfile(MINJAE_ID, "민재", false)));

        SharingListResponse response = service.getSharings(OWNER_ID, null);

        assertThat(response.items()).extracting(card -> card.receiver().name()).containsExactly("민재", "민재");
        verify(userProfileQuery).findProfilesByUserIds(List.of(MINJAE_ID, MINJAE_ID)); // 카드마다가 아니라 한 번 (중복 정리는 A 쪽)
    }

    @Test
    void 받는_사람이_탈퇴했으면_탈퇴한_사용자로_표시한다() {
        when(sharingCardReader.findCards(OWNER_ID, null)).thenReturn(List.of(
                card(40L, 31L, MINJAE_ID, STARTS_AT.plusHours(4), SharingStatus.SHARING, false,
                        SharedItemName.of(InformationType.MEDICATION, "혈압약"))));
        when(userProfileQuery.findProfilesByUserIds(List.of(MINJAE_ID)))
                .thenReturn(Map.of(MINJAE_ID, new UserProfile(MINJAE_ID, null, true))); // A는 탈퇴 사용자 이름을 비워서 돌려줌

        assertThat(service.getSharings(OWNER_ID, null).items())
                .extracting(SharingCard::receiver)
                .containsExactly(new Receiver(MINJAE_ID, SharingListService.WITHDRAWN_NAME));
    }

    @Test
    void 받는_사람을_찾을_수_없어도_목록은_실패하지_않고_탈퇴한_사용자로_표시한다() {
        when(sharingCardReader.findCards(OWNER_ID, null)).thenReturn(List.of(
                card(40L, 31L, MINJAE_ID, STARTS_AT.plusHours(4), SharingStatus.SHARING, false,
                        SharedItemName.of(InformationType.MEDICATION, "혈압약"))));
        when(userProfileQuery.findProfilesByUserIds(List.of(MINJAE_ID))).thenReturn(Map.of()); // 없는 ID는 결과에서 빠짐 (A 계약)

        assertThat(service.getSharings(OWNER_ID, null).items())
                .extracting(SharingCard::receiver)
                .containsExactly(new Receiver(MINJAE_ID, SharingListService.WITHDRAWN_NAME));
    }

    @Test
    void 카드가_없으면_빈_목록이고_사용자는_조회하지_않는다() {
        when(sharingCardReader.findCards(OWNER_ID, null)).thenReturn(List.of());

        assertThat(service.getSharings(OWNER_ID, null).items()).isEmpty();
        verify(userProfileQuery, never()).findProfilesByUserIds(any());
    }

    /** 카드 판단 결과 (도움 종류는 병원, 상황 ID는 응답에 쓰지 않는 내부 값) */
    private static SharingCardView card(Long assignmentId, Long requestId, Long receiverUserId, LocalDateTime accessUntil,
                                        SharingStatus status, boolean canReallow, SharedItemName... items) {
        return new SharingCardView(assignmentId, requestId, receiverUserId, 5L, HelpType.HOSPITAL, STARTS_AT, accessUntil,
                status, canReallow, List.of(items));
    }
}
