package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
 * 공유 다시 허용의 쓰기 처리를 DB 없이 확인합니다. (C, 저장소, PermissionWriter, 카드 판단 도우미는 Mock)
 *
 * - 철회됨 카드: 직접 철회한 항목만 새 ACTIVE 권한으로 다시 열고, 다시 공개한 항목 이름을 돌려줌 (삭제 항목 제외)
 * - 오류 구분: 카드가 아님(404), 요청 조건이나 관계, 상황 참여 실패, 이미 공유 중(409 NOT_ALLOWED),
 *   빈 카드는 삭제된 직접 철회 기록 여부로 NOTHING_TO_REALLOW와 NOT_ALLOWED를 나눔
 * - 잠금 단계(SharingCardLocker)는 실제 객체를 써서 잠금 순서까지 함께 확인합니다.
 * - 권한 생성과 GRANTED 이력 규칙 자체는 PermissionWriterTest, 실제 DB 동작은 SharingReallowIntegrationTest에서 확인합니다.
 */
class SharingReallowTransactionTest {

    // 고정 시각: 2026-10-02 15:00 (한국 시간). 요청은 17:00 종료라 지금은 진행 중
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);
    private static final List<PermissionStatus> CARD_STATUSES = List.of(PermissionStatus.ACTIVE, PermissionStatus.REVOKED); // 잠금 대상 상태

    private static final Long OWNER_ID = 10L;     // 정보 주인 (요청자, 로그인한 사용자)
    private static final Long ASSIGNEE_ID = 20L;  // 지금 담당자
    private static final Long REQUEST_ID = 31L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long ITEM_ID = 12L;
    private static final Long OTHER_ITEM_ID = 13L;

    private CareRequestSharingReader careRequestSharingReader;            // C 요청 조회 (Mock)
    private RequestInformationPermissionRepository permissionRepository; // 잠글 항목 ID 조회 (Mock)
    private PermissionWriter permissionWriter;                           // 잠금, 권한 생성 (Mock)
    private SharingCardReader sharingCardReader;                         // 관계, 상황 참여 확인 (Mock)
    private SharingReallowTransaction transaction;

    @BeforeEach
    void setUp() {
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        permissionWriter = mock(PermissionWriter.class);
        sharingCardReader = mock(SharingCardReader.class);
        // 잠금 단계(SharingCardLocker)는 실제 객체로, 그 아래 저장소와 PermissionWriter만 Mock
        transaction = new SharingReallowTransaction(careRequestSharingReader, sharingCardReader,
                new SharingCardLocker(permissionRepository, permissionWriter), permissionWriter,
                Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));

        // 기본은 "철회됨 카드 1장": 진행 중인 일반 요청, 관계와 상황 참여 유효, 혈압약(12번)과 당뇨약(13번) 직접 철회
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, OWNER_ID, ASSIGNMENT_ID);
        when(sharingCardReader.canReopen(any(), any())).thenReturn(true);
        when(permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES))
                .thenReturn(List.of(ITEM_ID, OTHER_ITEM_ID));
        givenItems(item(ITEM_ID, "혈압약"), item(OTHER_ITEM_ID, "당뇨약"));
        givenLockedPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED),
                revoked(101L, OTHER_ITEM_ID, RevokeReason.OWNER_REVOKED));
    }

    // === 다시 허용 ===

    @Test
    void 철회됨_카드를_다시_허용하면_직접_철회한_항목마다_새_ACTIVE_권한을_만든다() {
        SharingReallowResponse response = transaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        // 수행자 = 정보 주인, 부여 시각 = 지금, 일반 요청이라 접근 기한 없음
        verify(permissionWriter).createActive(REQUEST_ID, ASSIGNMENT_ID, OWNER_ID, OWNER_ID,
                List.of(ITEM_ID, OTHER_ITEM_ID), NOW, null);
        assertThat(response).isEqualTo(new SharingReallowResponse(ASSIGNMENT_ID, SharingStatus.SHARING, List.of(
                SharedItemName.of(InformationType.MEDICATION, "혈압약"),
                SharedItemName.of(InformationType.MEDICATION, "당뇨약"))));
    }

    @Test
    void 철회_후_삭제한_항목은_다시_열지_않는다() {
        givenItems(item(ITEM_ID, "혈압약")); // 당뇨약(13번)은 철회 뒤 삭제돼 잠글 원본이 없음

        SharingReallowResponse response = transaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        verify(permissionWriter).createActive(REQUEST_ID, ASSIGNMENT_ID, OWNER_ID, OWNER_ID, List.of(ITEM_ID), NOW, null);
        assertThat(response.sharedItems()).containsExactly(SharedItemName.of(InformationType.MEDICATION, "혈압약"));
    }

    @Test
    void 최신_권한이_직접_철회인_항목만_다시_연다() {
        // 12번: 공개 항목 제외(100번) → 다시 공개(101번 ACTIVE) → 직접 철회(101번). 13번: 공개 항목 제외만 (다시 열지 않음)
        RequestInformationPermission latest = revoked(101L, ITEM_ID, RevokeReason.OWNER_REVOKED);
        givenLockedPermissions(revoked(100L, ITEM_ID, RevokeReason.SHARING_EXCLUDED), latest,
                revoked(102L, OTHER_ITEM_ID, RevokeReason.SHARING_EXCLUDED));

        transaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        verify(permissionWriter).createActive(REQUEST_ID, ASSIGNMENT_ID, OWNER_ID, OWNER_ID, List.of(ITEM_ID), NOW, null);
    }

    // === 404: 공유 관리에 나오는 카드가 아님 ===

    @Test
    void 다른_사람의_공유면_404다() {
        assertError(() -> transaction.reallow(99L, ASSIGNMENT_ID, REQUEST_ID), SafetyCardErrorCode.SHARING_NOT_FOUND);
        verify(permissionWriter, never()).lockItems(any());
    }

    @Test
    void 요청이_없으면_404다() {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.empty());

        assertReallowError(SafetyCardErrorCode.SHARING_NOT_FOUND);
    }

    @Test
    void 급한_요청이면_404다() {
        // 급한 요청은 이번 범위에서 공유 관리 카드로 만들지 않음 (목록과 같은 범위)
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.URGENT, OWNER_ID, ASSIGNMENT_ID);

        assertReallowError(SafetyCardErrorCode.SHARING_NOT_FOUND);
    }

    // === 409 NOT_ALLOWED ===

    @Test
    void 끝난_요청이면_409이고_항목과_권한을_잠그지_않는다() {
        givenRequest(CareRequestStatus.COMPLETED, RequestType.GENERAL, OWNER_ID, ASSIGNMENT_ID);

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        verify(permissionWriter, never()).lockItems(any());
        verify(permissionWriter, never()).lockPermissionsById(any(), any());
    }

    @Test
    void 담당자가_바뀌었으면_409다() {
        // 대상 변경으로 지금 담당자의 지정이 41번 (카드 40번은 이전 담당자의 지정)
        givenRequest(CareRequestStatus.IN_PROGRESS, RequestType.GENERAL, OWNER_ID, 41L);

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
    }

    @Test
    void 관계가_끝났거나_담당자가_상황에서_나갔으면_409이고_잠그지_않는다() {
        when(sharingCardReader.canReopen(any(), any())).thenReturn(false);

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        verify(permissionWriter, never()).lockItems(any());
    }

    @Test
    void 이미_공유_중인_카드면_409다() {
        givenLockedPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED), active(101L, ITEM_ID),
                active(102L, OTHER_ITEM_ID));

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void ACTIVE와_직접_철회가_섞인_카드는_공유_중이라_409다() {
        // 철회 뒤 요청 수정으로 13번이 새로 공개된 경우. 12번을 다시 열려면 카드 전체를 철회한 뒤 다시 허용해야 함
        givenLockedPermissions(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED), active(101L, OTHER_ITEM_ID));

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
    }

    // === 빈 카드의 오류 구분 ===

    @Test
    void 철회한_항목이_모두_삭제됐으면_409_NOTHING_TO_REALLOW다() {
        // 남은 원본이 없고, 항목이 삭제된 권한 이력(information_id NULL)에 직접 철회 기록이 있음
        when(permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES)).thenReturn(List.of());
        givenItems();
        givenLockedPermissions(deleted(revoked(100L, ITEM_ID, RevokeReason.OWNER_REVOKED)),
                deleted(revoked(101L, OTHER_ITEM_ID, RevokeReason.OWNER_REVOKED)));

        assertReallowError(SafetyCardErrorCode.SHARING_NOTHING_TO_REALLOW);
        verify(permissionWriter, never()).createActive(any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void 직접_철회한_공유가_아니면_빈_카드여도_409_NOT_ALLOWED다() {
        // 남은 권한이 공개 항목 제외뿐이고, 삭제 이력에도 직접 철회 기록이 없음
        givenLockedPermissions(revoked(100L, ITEM_ID, RevokeReason.SHARING_EXCLUDED),
                deleted(revoked(101L, OTHER_ITEM_ID, RevokeReason.SHARING_EXCLUDED)));

        assertReallowError(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
    }

    // === 확인과 잠금 순서 ===

    @Test
    void 요청과_관계를_확인한_뒤_항목을_잠그고_권한을_잠근_다음_권한을_만든다() {
        transaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID);

        // 잠금 순서 C 요청 → 항목 → 권한 (#38). C 요청 행 잠금은 C 계약 반영 뒤 맨 앞에 추가
        InOrder order = inOrder(careRequestSharingReader, sharingCardReader, permissionRepository, permissionWriter);
        order.verify(careRequestSharingReader).findById(REQUEST_ID);
        order.verify(sharingCardReader).canReopen(any(), any());
        order.verify(permissionRepository).findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES); // ID만
        order.verify(permissionWriter).lockItems(List.of(ITEM_ID, OTHER_ITEM_ID));
        order.verify(permissionWriter).lockPermissionsById(ASSIGNMENT_ID, CARD_STATUSES);
        order.verify(permissionWriter).createActive(any(), any(), any(), any(), anyList(), any(), any());
    }

    // === 도우미 ===

    /** 기본 인자(정보 주인, 카드, 요청)로 다시 허용하면 이 오류 코드인지 */
    private void assertReallowError(SafetyCardErrorCode expected) {
        assertError(() -> transaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID), expected);
    }

    private static void assertError(ThrowingCallable call, SafetyCardErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    /**
     * C 요청 하나를 돌려주게 함 (종료 17:00, 담당자 20번)
     *
     * @param currentAssignmentId 지금 담당자의 지정 ID (카드 40번과 다르면 담당자가 바뀐 것)
     */
    private void givenRequest(CareRequestStatus status, RequestType type, Long requesterId, Long currentAssignmentId) {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, requesterId, currentAssignmentId, status, type,
                NOW.minusHours(1), NOW.plusHours(2), 5L, ASSIGNEE_ID, NOW.minusDays(1), HelpType.HOSPITAL)));
    }

    /** 항목 잠금(lockItems)이 돌려줄 원본 (인자가 없으면 모두 삭제됨) */
    private void givenItems(SafetyInformation... items) {
        when(permissionWriter.lockItems(any())).thenReturn(List.of(items));
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

    /** 이 카드의 끝난 권한 (사유로 직접 철회와 공개 항목 제외를 구분) */
    private static RequestInformationPermission revoked(Long id, Long informationId, RevokeReason reason) {
        RequestInformationPermission permission = active(id, informationId);
        permission.revoke(reason, NOW.minusHours(1));
        return permission;
    }

    /** 항목이 삭제돼 information_id가 비워진 기록으로 바꿈 (clearInformationId와 같은 결과) */
    private static RequestInformationPermission deleted(RequestInformationPermission permission) {
        ReflectionTestUtils.setField(permission, "informationId", null);
        return permission;
    }

    /** 잠금 조회로 얻은 정보 주인의 항목 원본 */
    private static SafetyInformation item(Long id, String title) {
        SafetyInformation item = SafetyInformation.create(OWNER_ID, InformationType.MEDICATION, title, "아침 1정");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
