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
 * - 공유 중 카드만 쓰는 경로(findSharingCards)가 canReallow용 A, B 조회를 하지 않는지도 확인합니다. (목록 findCards와 비교)
 * - 공유 중 목록 판단(findCards)의 요청 조건(종료 경계, 지금 담당자, 급한 요청, 요청 상태)과
 *   항목 판단(최신 권한, 섞인 카드, 공개 제외, 원본 삭제, 빈 카드), 철회됨 카드의 canReallow,
 *   받는 사람 필터와 정렬(카드 순서, 항목 작성순)을 확인합니다.
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
    private static final Long OTHER_ITEM_ID = 13L;

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
        givenItems(item(ITEM_ID, "혈압약"));
        givenRelation(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, VIEWER_ID)).thenReturn(true);
    }

    // === 받은 정보 (findViewableCards) ===

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

    // === 공유 중 목록: 요청 조건 ===

    @Test
    void 종료_시각이_되면_카드에서_빠진다() {
        // 종료 시각 미포함 (지금 < 종료 시각일 때만 공유 중). 스케줄러가 완료로 바꾸기 전이라 상태는 아직 진행 중
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, NOW, ASSIGNMENT_ID);

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 종료_1초_전이면_아직_공유_중이다() {
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, NOW.plusSeconds(1), ASSIGNMENT_ID);

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> assertThat(card.status()).isEqualTo(SharingStatus.SHARING));
    }

    @Test
    void 이_카드의_지정이_지금_담당자의_지정이_아니면_빼고_항목_원본을_조회하지_않는다() {
        // 수락 철회나 대상 변경으로 지금 담당자의 지정이 41번으로 바뀐 경우 (40번 지정의 권한이 아직 남아 있어도 뺌)
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, NOW.plusHours(2), 41L);

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
        verify(safetyInformationRepository, never()).findAllById(any()); // 남은 카드가 없으면 원본 조회 생략
    }

    @Test
    void 급한_요청은_이번_범위에서_카드로_만들지_않는다() {
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.URGENT, NOW.plusHours(2), ASSIGNMENT_ID);

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 수락_전_요청은_카드로_만들지_않는다() {
        givenRequest(CareRequestStatus.REQUESTED, RequestType.GENERAL, NOW.plusHours(2), ASSIGNMENT_ID);

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 끝난_요청은_카드로_만들지_않는다() {
        // 방어: 권한이 아직 ACTIVE로 남아 있어도 요청 상태로 뺌
        givenRequest(CareRequestStatus.COMPLETED, RequestType.GENERAL, NOW.plusHours(2), ASSIGNMENT_ID);

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    // === 공유 중 목록: 항목 판단 ===

    @Test
    void 과거에_철회했어도_다시_허용해_최신이_ACTIVE면_공유_중이고_항목은_한_번만_보인다() {
        // 같은 항목: 100번(직접 철회) → 101번(다시 허용으로 만든 ACTIVE). 최신 행 기준
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED), active(101L, ITEM_ID));

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.SHARING);
                    assertThat(card.canReallow()).isFalse(); // 공유 중 카드는 다시 허용 대상이 아님
                    assertThat(card.items()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "혈압약")); // 중복 없이 1개
                });
    }

    @Test
    void ACTIVE와_직접_철회가_섞인_카드는_공유_중이고_ACTIVE_항목만_보인다() {
        givenPermissions(active(100L, ITEM_ID), revoked(101L, OTHER_ITEM_ID, RevokeReason.OWNER_REVOKED));
        givenItems(item(ITEM_ID, "혈압약"), item(OTHER_ITEM_ID, "당뇨약"));

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.SHARING);
                    assertThat(card.items()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "혈압약"));
                });
    }

    @Test
    void 공개_항목에서_제외된_항목은_보이지_않고_그것만_남으면_빈_카드라_뺀다() {
        // 요청 수정으로 공개 항목에서 제외된 권한(SHARING_EXCLUDED)은 공유 중도, 직접 철회도 아님
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.SHARING_EXCLUDED));

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 항목_원본이_없으면_그_항목을_빼고_남은_항목이_없으면_카드를_뺀다() {
        // 방어: 권한은 남았는데 원본을 찾지 못한 경우. 실제 삭제는 권한의 information_id를 비워 저장소 조회에서 이미 빠짐 (후속 통합 테스트에서 확인 예정)
        givenItems();

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 다른_사람의_항목은_카드에_보이지_않는다() {
        // 정보 주인 = 요청자 방어. 원본 주인이 다르면 없는 항목으로 봄
        givenItems(item(ITEM_ID, "혈압약", 99L));

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    @Test
    void 원본이_사라진_ACTIVE_항목이_있어도_남은_직접_철회_항목으로_철회됨_카드가_된다() {
        // 상태는 원본 확인 뒤에 정함: ACTIVE였던 12번은 원본이 없어 빠지고, 남은 13번(직접 철회)으로 REVOKED
        givenPermissions(active(100L, ITEM_ID), revoked(101L, OTHER_ITEM_ID, RevokeReason.OWNER_REVOKED));
        givenItems(item(OTHER_ITEM_ID, "당뇨약"));

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.REVOKED);
                    assertThat(card.items()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "당뇨약"));
                });
    }

    @Test
    void 과거_직접_철회_기록이_있어도_최신이_공개_제외면_빈_카드라_뺀다() {
        // 같은 항목: 100번(직접 철회) → 101번(다시 허용 ACTIVE) → 요청 수정으로 공개 제외(SHARING_EXCLUDED)
        // 과거 철회 기록(100번)을 보면 REVOKED로 잘못 남으므로, 최신 행(101번)만 봐야 함
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED),
                revoked(101L, ITEM_ID, RevokeReason.SHARING_EXCLUDED));

        assertThat(reader.findCards(OWNER_ID, null)).isEmpty();
    }

    // === 공유 중 목록: 철회됨 카드의 canReallow ===

    @Test
    void 요청자에서_담당자로의_관계가_끝났으면_철회됨_카드는_남고_다시_허용은_안_된다() {
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));
        givenRelation(false);

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.REVOKED);
                    assertThat(card.canReallow()).isFalse();
                });
    }

    @Test
    void 담당자가_상황에_참여_중이_아니면_철회됨_카드는_남고_다시_허용은_안_된다() {
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, VIEWER_ID)).thenReturn(false); // 나가기, 제외

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.REVOKED);
                    assertThat(card.canReallow()).isFalse();
                });
    }

    @Test
    void 상황_ID가_없으면_철회됨_카드는_남고_다시_허용은_안_되며_참여_확인을_건너뛰지_않는다() {
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));
        givenRequest(null);

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.REVOKED);
                    assertThat(card.canReallow()).isFalse();
                });
        verify(situationParticipantReader, never()).isActiveParticipant(any(), any()); // 일반 요청은 상황 필수라 false로 끝냄
    }

    // === 공유 중 목록: 받는 사람 필터와 정렬 ===

    @Test
    void 받는_사람으로_거르면_다른_사람이_담당자인_카드는_뺀다() {
        assertThat(reader.findCards(OWNER_ID, 99L)).isEmpty(); // 지금 담당자는 VIEWER_ID(20)
    }

    @Test
    void 카드는_자동_회수_시각_빠른_순이고_같으면_지정_ID_순이다() {
        // 입력은 지정 40(종료 18:00), 42(종료 16:00), 41(종료 16:00) 순서여도 결과는 41, 42, 40
        // 종료 시각이 같은 카드를 지정 ID 역순으로 준비해, 동률 정렬이 빠지면 테스트가 실패하도록 함
        when(permissionRepository.findSharingCandidateAssignmentIds(OWNER_ID)).thenReturn(List.of(40L, 41L, 42L));
        when(permissionRepository.findItemPermissionsByAssignmentIds(List.of(40L, 41L, 42L))).thenReturn(List.of(
                permission(100L, 31L, 40L, ITEM_ID),
                permission(101L, 33L, 42L, ITEM_ID),
                permission(102L, 32L, 41L, ITEM_ID)));
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(
                31L, requestInfo(31L, 40L, NOW.plusHours(3), SITUATION_ID),
                32L, requestInfo(32L, 41L, NOW.plusHours(1), SITUATION_ID),
                33L, requestInfo(33L, 42L, NOW.plusHours(1), SITUATION_ID)));

        assertThat(reader.findCards(OWNER_ID, null))
                .extracting(SharingCardView::assignmentId)
                .containsExactly(41L, 42L, 40L);
    }

    @Test
    void 카드의_항목은_원본_조회_순서와_관계없이_작성순이다() {
        // 권한과 원본을 모두 13번(당뇨약), 12번(혈압약) 순으로 줘도 항목 ID(작성순)로 12번이 먼저
        givenPermissions(active(100L, OTHER_ITEM_ID), active(101L, ITEM_ID));
        givenItems(item(OTHER_ITEM_ID, "당뇨약"), item(ITEM_ID, "혈압약"));

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> assertThat(card.items()).containsExactly(
                        SharedItemName.of(InformationType.MEDICATION, "혈압약"),
                        SharedItemName.of(InformationType.MEDICATION, "당뇨약")));
    }

    // === 공유 중 카드만 쓰는 경로 (건수, 받은 정보) ===

    @Test
    void 공유_중_카드만_조회하면_철회됨_카드를_빼고_관계와_상황_참여를_조회하지_않는다() {
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));

        assertThat(reader.findSharingCards(OWNER_ID, null)).isEmpty();

        // 철회됨 카드의 '다시 허용' 버튼 계산(A, B 조회)은 버릴 카드라 하지 않음
        verify(relationshipQueryRepository, never()).existsBySourceUserIdAndTargetUserIdAndStatus(any(), any(), any());
        verify(situationParticipantReader, never()).isActiveParticipant(any(), any());
    }

    @Test
    void 공유_중_목록은_같은_철회됨_카드의_다시_허용_가능_여부를_계산한다() {
        // 위 테스트와 같은 카드를 목록 경로로 보면 REVOKED로 남고, A, B를 조회해 canReallow를 정함
        givenPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED));

        assertThat(reader.findCards(OWNER_ID, null))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.status()).isEqualTo(SharingStatus.REVOKED);
                    assertThat(card.canReallow()).isTrue(); // 관계 유효, 담당자 상황 참여 중 (setUp 기본값)
                });
        verify(relationshipQueryRepository).existsBySourceUserIdAndTargetUserIdAndStatus(
                OWNER_ID, VIEWER_ID, RelationshipStatus.ACTIVE);
        verify(situationParticipantReader).isActiveParticipant(SITUATION_ID, VIEWER_ID);
    }

    // === 도우미 ===

    private void givenPermissions(RequestInformationPermission... permissions) {
        when(permissionRepository.findItemPermissionsByAssignmentIds(List.of(ASSIGNMENT_ID))).thenReturn(List.of(permissions));
    }

    /** 진행 중인 일반 요청 (종료 17:00, 이 카드의 지정이 지금 담당자의 지정) */
    private void givenRequest(Long situationId) {
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, NOW.plusHours(2), ASSIGNMENT_ID, situationId);
    }

    /** 요청 조건만 바꾼 요청 (상황은 기본값) */
    private void givenRequest(CareRequestStatus status, RequestType type, LocalDateTime endsAt, Long currentAssignmentId) {
        givenRequest(status, type, endsAt, currentAssignmentId, SITUATION_ID);
    }

    /**
     * 요청 조건을 지정한 요청 하나를 C 조회 결과로 돌려줍니다. (시작은 1시간 전, 받는 사람은 VIEWER_ID)
     *
     * @param currentAssignmentId 지금 담당자의 지정 ID (카드의 지정 ASSIGNMENT_ID와 다르면 지금 담당자의 공유가 아님)
     */
    private void givenRequest(CareRequestStatus status, RequestType type, LocalDateTime endsAt,
                              Long currentAssignmentId, Long situationId) {
        when(careRequestSharingReader.findAllByIds(anyCollection())).thenReturn(Map.of(REQUEST_ID, new SharingRequestInfo(
                REQUEST_ID, OWNER_ID, currentAssignmentId, status, type,
                NOW.minusHours(1), endsAt, situationId, VIEWER_ID, NOW.minusDays(1), HelpType.HOSPITAL)));
    }

    /** 정렬 테스트용: 진행 중인 일반 요청 (요청 ID와 지금 담당자의 지정 ID, 종료 시각만 다르게) */
    private static SharingRequestInfo requestInfo(Long requestId, Long assignmentId, LocalDateTime endsAt, Long situationId) {
        return new SharingRequestInfo(requestId, OWNER_ID, assignmentId, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                NOW.minusHours(1), endsAt, situationId, VIEWER_ID, NOW.minusDays(1), HelpType.HOSPITAL);
    }

    /** 저장소가 돌려줄 항목 원본 (인자가 없으면 원본 없음) */
    private void givenItems(SafetyInformation... items) {
        when(safetyInformationRepository.findAllById(any())).thenReturn(List.of(items));
    }

    private void givenRelation(boolean active) {
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                OWNER_ID, VIEWER_ID, RelationshipStatus.ACTIVE)).thenReturn(active);
    }

    private static RequestInformationPermission active(Long id, Long informationId) {
        return permission(id, REQUEST_ID, ASSIGNMENT_ID, informationId);
    }

    /** ACTIVE 권한 (요청과 지정을 지정할 수 있는 버전. 정렬 테스트처럼 카드가 여러 장일 때 사용) */
    private static RequestInformationPermission permission(Long id, Long requestId, Long assignmentId, Long informationId) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                requestId, assignmentId, informationId, InformationType.MEDICATION, NOW.minusDays(1), null);
        ReflectionTestUtils.setField(permission, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return permission;
    }

    private static RequestInformationPermission revoked(Long id, Long informationId, RevokeReason reason) {
        RequestInformationPermission permission = active(id, informationId);
        permission.revoke(reason, NOW.minusHours(1));
        return permission;
    }

    private static SafetyInformation item(Long id, String title) {
        return item(id, title, OWNER_ID);
    }

    private static SafetyInformation item(Long id, String title, Long ownerId) {
        SafetyInformation item = SafetyInformation.create(ownerId, InformationType.MEDICATION, title, "아침 1정");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
