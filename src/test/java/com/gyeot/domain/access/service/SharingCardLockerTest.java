package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.access.service.SharingCardLocker.LockedCard;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공유 카드 잠금 단계가 잠금 순서를 지키고 결과를 나누어 돌려주는지 DB 없이 확인합니다. (저장소와 PermissionWriter는 Mock)
 *
 * - 항목 ID만 조회 → 항목 잠금 → 권한 잠금 순서
 * - 잠근 원본 중 정보 주인의 것만 원본으로 돌려줌
 * - 항목이 삭제된 권한(information_id NULL)은 버리지 않고 삭제 이력으로 따로 돌려줌
 */
class SharingCardLockerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);
    private static final List<PermissionStatus> CARD_STATUSES = List.of(PermissionStatus.ACTIVE, PermissionStatus.REVOKED);

    private static final Long OWNER_ID = 10L;     // 정보 주인
    private static final Long ASSIGNMENT_ID = 40L; // 잠글 카드
    private static final Long ITEM_ID = 12L;
    private static final Long OTHER_ITEM_ID = 13L;

    private RequestInformationPermissionRepository permissionRepository; // 잠글 항목 ID 조회 (Mock)
    private PermissionWriter permissionWriter;                           // 항목, 권한 잠금 (Mock, 잠금 자체는 PermissionWriterTest)
    private SharingCardLocker locker;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        permissionWriter = mock(PermissionWriter.class);
        locker = new SharingCardLocker(permissionRepository, permissionWriter);

        when(permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES))
                .thenReturn(List.of(ITEM_ID, OTHER_ITEM_ID));
    }

    @Test
    void 항목_ID만_조회한_뒤_항목을_잠그고_그다음_권한을_잠근다() {
        locker.lock(OWNER_ID, ASSIGNMENT_ID);

        // 잠금 순서 항목 → 권한 (#38). 권한 엔티티는 잠금 조회로 처음 읽음
        InOrder order = inOrder(permissionRepository, permissionWriter);
        order.verify(permissionRepository).findInformationIdsByAssignmentIdAndStatusIn(ASSIGNMENT_ID, CARD_STATUSES);
        order.verify(permissionWriter).lockItems(List.of(ITEM_ID, OTHER_ITEM_ID)); // 조회한 ID를 그대로 잠금
        order.verify(permissionWriter).lockPermissionsById(ASSIGNMENT_ID, CARD_STATUSES);
    }

    @Test
    void 잠근_원본_중_정보_주인의_것만_원본으로_돌려준다() {
        SafetyInformation mine = item(ITEM_ID, OWNER_ID);
        when(permissionWriter.lockItems(List.of(ITEM_ID, OTHER_ITEM_ID)))
                .thenReturn(List.of(mine, item(OTHER_ITEM_ID, 99L))); // 13번은 다른 사람 항목 (방어)

        LockedCard card = locker.lock(OWNER_ID, ASSIGNMENT_ID);

        assertThat(card.items()).containsOnlyKeys(ITEM_ID);
        assertThat(card.items().get(ITEM_ID)).isSameAs(mine); // 잠근 원본을 다시 조회하지 않고 그대로 씀
    }

    @Test
    void 항목이_삭제된_권한은_버리지_않고_삭제_이력으로_따로_돌려준다() {
        RequestInformationPermission valid = permission(100L, ITEM_ID);
        RequestInformationPermission deleted = permission(101L, null);  // 항목 삭제로 information_id가 비워진 직접 철회 기록
        RequestInformationPermission other = permission(102L, OTHER_ITEM_ID);
        when(permissionWriter.lockPermissionsById(ASSIGNMENT_ID, CARD_STATUSES)).thenReturn(List.of(valid, deleted, other));

        LockedCard card = locker.lock(OWNER_ID, ASSIGNMENT_ID);

        assertThat(card.permissions()).containsExactly(valid, other); // 카드 판단용 (권한 ID 순서 유지)
        assertThat(card.deletedHistory()).containsExactly(deleted);   // 오류 구분용
    }

    /** 직접 철회한 권한. informationId가 null이면 항목이 삭제된 기록 */
    private static RequestInformationPermission permission(Long id, Long informationId) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                31L, ASSIGNMENT_ID, ITEM_ID, InformationType.MEDICATION, NOW.minusDays(1), null);
        permission.revoke(RevokeReason.OWNER_REVOKED, NOW.minusHours(1));
        ReflectionTestUtils.setField(permission, "id", id);                       // DB 없이 테스트하므로 ID를 직접 넣음
        ReflectionTestUtils.setField(permission, "informationId", informationId); // 삭제 기록은 NULL (clearInformationId와 같은 결과)
        return permission;
    }

    /** 잠금 조회로 얻은 항목 원본 */
    private static SafetyInformation item(Long id, Long ownerId) {
        SafetyInformation item = SafetyInformation.create(ownerId, InformationType.MEDICATION, "혈압약", "아침 1정");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
