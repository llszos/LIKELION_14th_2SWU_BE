package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 카드 판단을 DB 없이 확인합니다. (저장소와 C, A, B 조회는 Mock)
 *
 * - 지금은 받은 정보용 판단(findViewableCards)의 관계, 상황 참여, 철회 카드 제외를 확인합니다.
 * - 공유 중 목록 판단(findCards)의 과거 철회, 공개 제외, 삭제, 빈 카드, 종료 경계는 다음 커밋에서 이 파일에 추가합니다.
 */
class SharingCardReaderTest {

    // 고정 시각: 2026-10-02 15:00 (한국 시간). 요청은 17:00 종료라 지금은 진행 중
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자)
    private static final Long VIEWER_ID = 20L;    // 받는 사람 (지금 담당자)
    private static final Long REQUEST_ID = 31L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long SITUATION_ID = 5L;
    private static final Long ITEM_ID = 12L;

    private RequestInformationPermissionRepository permissionRepository;
    private CareRequestSharingReader careRequestSharingReader;
    private SafetyInformationRepository safetyInformationRepository;
    private RelationshipQueryRepository relationshipQueryRepository;
    private SituationParticipantReader situationParticipantReader;
    private SharingCardReader reader;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        relationshipQueryRepository = mock(RelationshipQueryRepository.class);
        situationParticipantReader = mock(SituationParticipantReader.class);
        reader = new SharingCardReader(permissionRepository, careRequestSharingReader, safetyInformationRepository,
                relationshipQueryRepository, situationParticipantReader, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));

        // 기본은 "받는 사람이 볼 수 있는 카드 1장": 진행 중인 일반 요청, 지금 담당자, 혈압약 하나 공유 중, 관계 유효, 상황 참여 중
        when(permissionRepository.findSharingCandidateAssignmentIds(OWNER_ID)).thenReturn(List.of(ASSIGNMENT_ID));
        givenPermissions(active(100L, ITEM_ID));
        givenRequest(SITUATION_ID);
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(item(ITEM_ID, "혈압약")));
        givenRelation(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, VIEWER_ID)).thenReturn(true);
    }

    @Test
    void 관계가_유효하고_상황에_참여_중이면_받는_사람이_볼_수_있는_카드다() {
        assertThat(reader.findViewableCards(OWNER_ID, VIEWER_ID))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.SHARING);
                    assertThat(card.items()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "혈압약"));
                });
    }

    @Test
    void 정보_주인에서_받는_사람으로의_관계가_없으면_빈_목록이고_카드를_조회하지_않는다() {
        givenRelation(false);

        assertThat(reader.findViewableCards(OWNER_ID, VIEWER_ID)).isEmpty();
        verify(permissionRepository, never()).findSharingCandidateAssignmentIds(any()); // 관계부터 막혀 카드 판단을 하지 않음
    }

    @Test
    void 받는_사람이_상황에_참여_중이_아니면_빈_목록이다() {
        // 상황 나가기(LEFT)와 제외(REMOVED)는 B 조회에서 모두 ACTIVE 참여자가 아님(false)으로 돌아옴
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, VIEWER_ID)).thenReturn(false);

        assertThat(reader.findViewableCards(OWNER_ID, VIEWER_ID)).isEmpty();
    }

    @Test
    void 일반_요청인데_상황_ID가_없으면_참여_확인을_건너뛰지_않고_뺀다() {
        givenRequest(null);

        assertThat(reader.findViewableCards(OWNER_ID, VIEWER_ID)).isEmpty();
        verify(situationParticipantReader, never()).isActiveParticipant(any(), any());
    }

    @Test
    void 정보_주인이_직접_철회한_카드는_받는_사람에게_보이지_않는다() {
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));

        assertThat(reader.findViewableCards(OWNER_ID, VIEWER_ID)).isEmpty();
    }

    // === 도우미 ===

    private void givenPermissions(RequestInformationPermission... permissions) {
        when(permissionRepository.findItemPermissionsByAssignmentIds(List.of(ASSIGNMENT_ID))).thenReturn(List.of(permissions));
    }

    /** 진행 중인 일반 요청 (종료 17:00, 이 카드의 지정이 지금 담당자의 지정) */
    private void givenRequest(Long situationId) {
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(REQUEST_ID, new SharingRequestInfo(
                REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                NOW.minusHours(1), NOW.plusHours(2), situationId, VIEWER_ID, NOW.minusDays(1), HelpType.HOSPITAL)));
    }

    private void givenRelation(boolean active) {
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                OWNER_ID, VIEWER_ID, RelationshipStatus.ACTIVE)).thenReturn(active);
    }

    private static RequestInformationPermission active(Long id, Long informationId) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, InformationType.MEDICATION, NOW.minusDays(1), null);
        ReflectionTestUtils.setField(permission, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return permission;
    }

    private static RequestInformationPermission revoked(Long id, Long informationId, RevokeReason reason) {
        RequestInformationPermission permission = active(id, informationId);
        permission.revoke(reason, NOW.minusHours(1));
        return permission;
    }

    private static SafetyInformation item(Long id, String title) {
        SafetyInformation item = SafetyInformation.create(OWNER_ID, InformationType.MEDICATION, title, "아침 1정");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
