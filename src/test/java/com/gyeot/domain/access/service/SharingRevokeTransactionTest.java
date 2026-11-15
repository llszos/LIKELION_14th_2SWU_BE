package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.event.PermissionRevokedEvent;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 직접 철회의 쓰기 처리를 DB 없이 확인합니다. (C, 저장소, PermissionWriter, 카드 판단 도우미는 Mock)
 *
 * - 카드 상태별 처리: 공유 중이면 회수와 이벤트 1건, 이미 철회됨이면 변경 없음, 요청 조건 실패나 빈 카드면 404
 * - 잠금 단계(SharingCardLocker)는 실제 객체를 써서, 분리 전과 같은 잠금 순서와 결과인지 함께 확인합니다.
 * - 최신 권한 기준 회수 대상, 잠금 순서(요청 확인 → 항목 → 권한 → 회수)
 * - 권한 회수와 이력 규칙 자체는 PermissionWriterTest, 판단 규칙 자체는 SharingCardReaderTest에서 확인합니다.
 * - C 요청 행 잠금과 실제 DB 동시성은 C 잠금 계약 반영 뒤 통합 테스트에서 확인합니다.
 */
class SharingRevokeTransactionTest {

    // 고정 시각: 2026-10-02 15:00 (한국 시간). 요청은 17:00 종료라 지금은 진행 중
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);
    private static final List<PermissionStatus> CARD_STATUSES = List.of(PermissionStatus.ACTIVE, PermissionStatus.REVOKED); // 서비스와 같은 잠금 대상 상태

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자, 로그인한 사용자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long REQUEST_ID = 31L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long ITEM_ID = 12L;
    private static final Long OTHER_ITEM_ID = 13L;

    private CareRequestSharingReader careRequestSharingReader;            // C 요청 조회 (Mock)
    private RequestInformationPermissionRepository permissionRepository; // 잠글 항목 ID 조회 (Mock)
    private PermissionWriter permissionWriter;                           // 잠금, 회수 (Mock, 규칙 자체는 PermissionWriterTest)
    private SharingCardReader sharingCardReader;                         // canReallow 계산 (Mock)
    private ApplicationEventPublisher eventPublisher;                    // 이벤트 발행 확인 (Mock)
    private SharingRevokeTransaction transaction;

    @BeforeEach
    void setUp() {
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        permissionWriter = mock(PermissionWriter.class);
        sharingCardReader = mock(SharingCardReader.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // 잠금 단계(SharingCardLocker)는 실제 객체로, 그 아래 저장소와 PermissionWriter만 Mock (잠금 순서까지 함께 확인)
        transaction = new SharingRevokeTransaction(careRequestSharingReader,
                new SharingCardLocker(permissionRepository, permissionWriter), permissionWriter,
                sharingCardReader, eventPublisher, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));

        // 기본은 "공유 중 카드 1장": 진행 중인 일반 요청, 지금 담당자의 지정, 혈압약(12번)과 당뇨약(13번) 공유 중
        givenRequest(CareRequestStatus.IN_PROGRESS);
        when(permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES))
                .thenReturn(List.of(ITEM_ID, OTHER_ITEM_ID));
        when(permissionWriter.lockItems(List.of(ITEM_ID, OTHER_ITEM_ID)))
                .thenReturn(List.of(item(ITEM_ID, "혈압약", OWNER_ID), item(OTHER_ITEM_ID, "당뇨약", OWNER_ID)));
        givenLockedPermissions(active(100L, ITEM_ID), active(101L, OTHER_ITEM_ID)); // 회수 대상을 볼 테스트는 다시 지정
        when(permissionWriter.revokeByOwner(anyList(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // 넘긴 ACTIVE 권한을 모두 끝냈다고 가정
        when(sharingCardReader.canReopen(any(), any())).thenReturn(true);
    }

    // === 카드 상태별 처리 ===

    @Test
    void 공유_중_카드를_철회하면_ACTIVE_권한을_모두_회수하고_이벤트를_1건_발행한다() {
        RequestInformationPermission first = active(100L, ITEM_ID);
        RequestInformationPermission second = active(101L, OTHER_ITEM_ID);
        givenLockedPermissions(first, second);

        SharingRevokeResponse response = transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        assertThat(response).isEqualTo(new SharingRevokeResponse(ASSIGNMENT_ID, SharingStatus.REVOKED, true));
        verify(permissionWriter).revokeByOwner(List.of(first, second), OWNER_ID, NOW); // 잠금 조회한 그 객체 (작성순)

        // 항목이 2개여도 카드(요청, 담당자)당 1건, 받는 사람은 지금 담당자
        verify(eventPublisher).publishEvent(new PermissionRevokedEvent(
                REQUEST_ID, ASSIGNMENT_ID, OWNER_ID, ASSIGNEE_ID, RevokeReason.OWNER_REVOKED));
    }

    @Test
    void 과거_철회_기록이_있으면_최신_ACTIVE_권한만_회수_대상이다() {
        // 12번: 100번(직접 철회) → 101번(다시 허용 ACTIVE). 13번: 102번(직접 철회, 섞인 카드)
        RequestInformationPermission reallowed = active(101L, ITEM_ID);
        givenLockedPermissions(revoked(100L, ITEM_ID), reallowed, revoked(102L, OTHER_ITEM_ID));

        transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        verify(permissionWriter).revokeByOwner(List.of(reallowed), OWNER_ID, NOW);
    }

    @Test
    void 이미_철회된_카드는_아무것도_바꾸지_않고_철회됨으로_응답한다() {
        // 재철회 또는 동시 철회에서 늦은 쪽: 잠근 뒤 보니 최신 권한이 모두 직접 철회
        givenLockedPermissions(revoked(100L, ITEM_ID), revoked(101L, OTHER_ITEM_ID));
        when(sharingCardReader.canReopen(any(), any())).thenReturn(false); // 버튼은 관계, 상황 참여 결과 그대로

        SharingRevokeResponse response = transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        assertThat(response).isEqualTo(new SharingRevokeResponse(ASSIGNMENT_ID, SharingStatus.REVOKED, false));
        verify(permissionWriter, never()).revokeByOwner(anyList(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 실제로_회수한_권한이_없으면_이벤트를_발행하지_않는다() {
        // 방어: 넘긴 권한이 이미 끝나 있었던 경우 (PermissionWriter가 빈 목록을 돌려줌)
        when(permissionWriter.revokeByOwner(anyList(), any(), any())).thenReturn(List.of());

        transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // === 404 ===

    @Test
    void 끝난_요청이면_404이고_항목과_권한을_잠그지_않는다() {
        givenRequest(CareRequestStatus.COMPLETED);

        assertNotFound();
        verify(permissionWriter, never()).lockItems(any());
        verify(permissionWriter, never()).lockPermissionsById(any(), any());
    }

    @Test
    void 요청이_없으면_404다() {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.empty());

        assertNotFound();
    }

    @Test
    void 다른_사람의_공유면_404다() {
        // 로그인한 사용자가 요청자가 아님 (다른 사람의 카드 ID를 넣은 경우)
        assertThatThrownBy(() -> transaction.revoke(99L, ASSIGNMENT_ID, REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARING_NOT_FOUND);
        verify(permissionWriter, never()).lockItems(any());
    }

    @Test
    void 항목이_모두_삭제돼_남은_항목이_없으면_404이고_회수하지_않는다() {
        // 잠그러 갔더니 원본이 모두 삭제됨 (동시 삭제 포함). 목록에서도 빠진 빈 카드
        when(permissionWriter.lockItems(any())).thenReturn(List.of());

        assertNotFound();
        verify(permissionWriter, never()).revokeByOwner(anyList(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 다른_사람의_항목은_원본으로_보지_않는다() {
        // 방어: 잠근 원본의 주인이 정보 주인이 아니면 없는 항목으로 봐 빈 카드
        when(permissionWriter.lockItems(any())).thenReturn(List.of(item(ITEM_ID, "혈압약", 99L)));
        givenLockedPermissions(active(100L, ITEM_ID));

        assertNotFound();
    }

    // === 잠금 순서 ===

    @Test
    void 요청_확인_뒤_항목을_잠그고_권한을_잠근_다음_회수한다() {
        transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        // 잠금 순서 C 요청 → 항목 → 권한 (#38). C 요청 행 잠금은 C 계약 반영 뒤 맨 앞에 추가
        InOrder order = inOrder(careRequestSharingReader, permissionRepository, permissionWriter);
        order.verify(careRequestSharingReader).findById(REQUEST_ID);
        order.verify(permissionRepository).findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES); // ID만
        order.verify(permissionWriter).lockItems(List.of(ITEM_ID, OTHER_ITEM_ID));
        order.verify(permissionWriter).lockPermissionsById(ASSIGNMENT_ID, CARD_STATUSES);
        order.verify(permissionWriter).revokeByOwner(anyList(), any(), any());
    }

    // === 도우미 ===

    /** 기본 인자(정보 주인, 카드, 요청)로 철회하면 404 SHARING_NOT_FOUND인지 */
    private void assertNotFound() {
        assertThatThrownBy(() -> transaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARING_NOT_FOUND);
    }

    /** 이 카드의 지정이 지금 담당자의 지정인 일반 요청 (종료 17:00) */
    private void givenRequest(CareRequestStatus status) {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, status, RequestType.GENERAL,
                NOW.minusHours(1), NOW.plusHours(2), 5L, ASSIGNEE_ID, NOW.minusDays(1), HelpType.HOSPITAL)));
    }

    /** 권한 잠금 조회(lockPermissionsById)가 돌려줄 권한 (잠근 뒤의 최신 상태라고 가정) */
    private void givenLockedPermissions(RequestInformationPermission... permissions) {
        when(permissionWriter.lockPermissionsById(ASSIGNMENT_ID, CARD_STATUSES)).thenReturn(List.of(permissions));
    }

    /** 이 카드(요청 31, 지정 40)의 ACTIVE 권한 */
    private static RequestInformationPermission active(Long id, Long informationId) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, InformationType.MEDICATION, NOW.minusDays(1), null);
        ReflectionTestUtils.setField(permission, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return permission;
    }

    /** 이 카드의 직접 철회(OWNER_REVOKED) 권한 */
    private static RequestInformationPermission revoked(Long id, Long informationId) {
        RequestInformationPermission permission = active(id, informationId);
        permission.revoke(RevokeReason.OWNER_REVOKED, NOW.minusHours(1));
        return permission;
    }

    /** 잠금 조회로 얻은 항목 원본 (주인을 바꿔 다른 사람의 항목도 만듦) */
    private static SafetyInformation item(Long id, String title, Long ownerId) {
        SafetyInformation item = SafetyInformation.create(ownerId, InformationType.MEDICATION, title, "아침 1정");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
