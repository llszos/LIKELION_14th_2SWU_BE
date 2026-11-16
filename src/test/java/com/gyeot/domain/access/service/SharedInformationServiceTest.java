package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedInformationResponse;
import com.gyeot.domain.access.dto.response.SharedInformationResponse.SharedCategory;
import com.gyeot.domain.access.dto.response.SharedInformationResponse.SharedItem;
import com.gyeot.domain.access.dto.response.UnavailableReason;
import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 전달받은 정보 조회의 판단 규칙을 DB 없이 확인합니다. (Reader와 저장소는 Mock)
 *
 * - 404 대상, 볼 수 없는 이유 7가지 중 이번 범위에서 나오는 것, 급한 요청 차단
 * - 직접 철회는 항목별 최신 권한으로 판단 (철회 → 다시 허용 → 전체 삭제, 전체 제외 회귀)
 * - 표시 값('갱신됨', '추가됨'), 분류와 항목 순서, 원본이 없거나 다른 주인의 항목 제외, VIEWED 기록
 *
 * 스냅샷 기준의 조회 경합과 이력 저장 실패 롤백은 실제 MySQL이 필요해 통합 테스트에서 확인합니다.
 */
class SharedInformationServiceTest {

    // 고정 시각: 2026-10-02 15:00 (한국 시간). 요청은 14:00 시작, 17:00 종료라 지금은 진행 중
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 2, 14, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 10, 2, 17, 0);
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    private static final Long REQUEST_ID = 31L;
    private static final Long REQUESTER_ID = 10L;  // 요청자 = 정보 주인
    private static final Long ASSIGNEE_ID = 7L;    // 조회하는 담당자
    private static final Long ASSIGNMENT_ID = 40L; // 담당자의 대상 지정
    private static final Long SITUATION_ID = 5L;

    private CareRequestSharingReader careRequestSharingReader;
    private RelationshipQueryRepository relationshipQueryRepository;
    private SituationParticipantReader situationParticipantReader;
    private RequestInformationPermissionRepository permissionRepository;
    private SafetyInformationRepository safetyInformationRepository;
    private InformationAccessLogRepository accessLogRepository;
    private SharedInformationService service;

    @BeforeEach
    void setUp() {
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        relationshipQueryRepository = mock(RelationshipQueryRepository.class);
        situationParticipantReader = mock(SituationParticipantReader.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        accessLogRepository = mock(InformationAccessLogRepository.class);
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        service = new SharedInformationService(careRequestSharingReader, relationshipQueryRepository,
                situationParticipantReader, permissionRepository, safetyInformationRepository, accessLogRepository, clock);

        // 기본은 "볼 수 있는 상태": 지정된 적 있음, 진행 중인 일반 요청의 지금 담당자, 관계 유효, 상황 참여 중
        when(careRequestSharingReader.hasBeenAssigned(REQUEST_ID, ASSIGNEE_ID)).thenReturn(true);
        givenRequest(request(CareRequestStatus.IN_PROGRESS));
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                REQUESTER_ID, ASSIGNEE_ID, RelationshipStatus.ACTIVE)).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);
        givenPermissions(); // 권한 없음 (테스트마다 필요하면 다시 정함)
    }

    // === 404 ===

    @Test
    void 이_요청의_대상이었던_적이_없으면_404이고_요청을_조회하지_않는다() {
        when(careRequestSharingReader.hasBeenAssigned(REQUEST_ID, ASSIGNEE_ID)).thenReturn(false);

        assertSharedInfoNotFound();
        verify(careRequestSharingReader, never()).findById(any()); // 요청이 있는지도 알려 주지 않음
        verifyNoInteractions(permissionRepository, safetyInformationRepository, accessLogRepository);
    }

    @Test
    void 지정_이력만_있고_요청이_없으면_404이다() {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.empty());

        assertSharedInfoNotFound();
    }

    @Test
    void 급한_요청은_이번_범위에서_404이고_권한과_항목을_조회하지_않는다() {
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, CareRequestStatus.ACCEPTED,
                RequestType.URGENT, null, null, null, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL));

        assertSharedInfoNotFound();
        verifyNoInteractions(permissionRepository, safetyInformationRepository, accessLogRepository);
    }

    // === 볼 수 없는 이유 ===

    @Test
    void 요청됨이고_지금_대상이면_NOT_ACCEPTED이다() {
        // 수락 전이라 C의 현재 담당자 값이 없음. 지금 대상인지는 isCurrentTarget으로 따로 확인
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, null, CareRequestStatus.REQUESTED,
                RequestType.GENERAL, STARTS_AT, ENDS_AT, SITUATION_ID, null, null, HelpType.HOSPITAL));
        when(careRequestSharingReader.isCurrentTarget(REQUEST_ID, ASSIGNEE_ID)).thenReturn(true);

        assertUnavailable(UnavailableReason.NOT_ACCEPTED);
        verifyNoInteractions(permissionRepository); // 지금 담당자가 아니면 권한을 읽지 않음
    }

    @Test
    void 요청됨이고_예전_대상이면_ASSIGNMENT_CLOSED이다() {
        // 대상 변경이나 수락 철회로 내 지정이 마감된 뒤 다시 요청됨 상태 (다른 사람에게 넘어감)
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, null, CareRequestStatus.REQUESTED,
                RequestType.GENERAL, STARTS_AT, ENDS_AT, SITUATION_ID, null, null, HelpType.HOSPITAL));
        when(careRequestSharingReader.isCurrentTarget(REQUEST_ID, ASSIGNEE_ID)).thenReturn(false);

        assertUnavailable(UnavailableReason.ASSIGNMENT_CLOSED);
        verifyNoInteractions(permissionRepository); // 지금 담당자가 아니면 권한을 읽지 않음
    }

    @Test
    void 끝난_요청은_상태별_이유를_내려준다() {
        // 완료는 마지막 담당자가 남아 있어도 볼 수 없음
        givenRequest(request(CareRequestStatus.COMPLETED));
        assertUnavailable(UnavailableReason.REQUEST_COMPLETED);

        // 취소와 만료는 C가 지정을 마감해 현재 담당자 값이 없음
        givenRequest(closedRequest(CareRequestStatus.CANCELED));
        assertUnavailable(UnavailableReason.REQUEST_CANCELED);

        givenRequest(closedRequest(CareRequestStatus.EXPIRED));
        assertUnavailable(UnavailableReason.REQUEST_EXPIRED);
    }

    @Test
    void 종료_시각이_되면_완료로_바뀌기_전에도_볼_수_없다() {
        // 종료 시각 = 지금 (스케줄러가 아직 완료로 바꾸지 않은 1분 사이)
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, CareRequestStatus.IN_PROGRESS,
                RequestType.GENERAL, STARTS_AT, NOW, SITUATION_ID, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL));

        assertUnavailable(UnavailableReason.REQUEST_ENDED_BY_TIME);
    }

    @Test
    void 지금_담당자가_아니면_ASSIGNMENT_CLOSED이다() {
        // 수락 철회 후 다른 사람(8번)이 담당자가 됨
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, 41L, CareRequestStatus.ACCEPTED,
                RequestType.GENERAL, STARTS_AT, ENDS_AT, SITUATION_ID, 8L, ACCEPTED_AT, HelpType.HOSPITAL));

        assertUnavailable(UnavailableReason.ASSIGNMENT_CLOSED);
        verifyNoInteractions(permissionRepository);
    }

    @Test
    void 요청자에서_담당자로의_관계가_끝났으면_ASSIGNMENT_CLOSED이다() {
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                REQUESTER_ID, ASSIGNEE_ID, RelationshipStatus.ACTIVE)).thenReturn(false);
        givenPermissions(active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT)); // 권한이 ACTIVE여도 막음

        assertUnavailable(UnavailableReason.ASSIGNMENT_CLOSED);
    }

    @Test
    void 상황에_참여_중이_아니면_ASSIGNMENT_CLOSED이다() {
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(false);
        givenPermissions(active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT));

        assertUnavailable(UnavailableReason.ASSIGNMENT_CLOSED);
    }

    @Test
    void 일반_요청인데_상황_ID가_없으면_참여_확인을_건너뛰지_않고_막는다() {
        givenRequest(new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, CareRequestStatus.ACCEPTED,
                RequestType.GENERAL, STARTS_AT, ENDS_AT, null, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL));
        givenPermissions(active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT));

        assertUnavailable(UnavailableReason.ASSIGNMENT_CLOSED);
        verify(situationParticipantReader, never()).isActiveParticipant(any(), any());
    }

    @Test
    void 정보_주인이_직접_철회했으면_OWNER_REVOKED이다() {
        givenPermissions(
                revoked(100L, 12L, InformationType.MEDICATION, RevokeReason.OWNER_REVOKED),
                revoked(101L, 22L, InformationType.ENTRY_METHOD, RevokeReason.OWNER_REVOKED));

        assertUnavailable(UnavailableReason.OWNER_REVOKED);
        verify(accessLogRepository, never()).saveAll(any());
    }

    @Test
    void 철회_후_다시_허용하고_모두_삭제하면_예전_철회_기록으로_막지_않고_빈_목록이다() {
        // 삭제는 그 항목의 모든 권한 행의 항목 ID를 NULL로 바꿈 (ERD 9.5)
        givenPermissions(
                deletedItem(revoked(100L, 12L, InformationType.MEDICATION, RevokeReason.OWNER_REVOKED)), // 예전 철회
                deletedItem(revoked(110L, 12L, InformationType.MEDICATION, RevokeReason.ITEM_DELETED))); // 다시 허용 후 삭제

        assertAccessibleAndEmpty();
    }

    @Test
    void 철회_후_다시_허용하고_모두_공개에서_제외하면_예전_철회_기록으로_막지_않고_빈_목록이다() {
        // 같은 항목의 최신 행이 공개 항목 제외라 직접 철회 상태가 아님
        givenPermissions(
                revoked(100L, 12L, InformationType.MEDICATION, RevokeReason.OWNER_REVOKED),     // 예전 철회
                revoked(110L, 12L, InformationType.MEDICATION, RevokeReason.SHARING_EXCLUDED)); // 다시 허용 후 제외

        assertAccessibleAndEmpty();
    }

    @Test
    void 볼_수_없을_때도_접근_기한을_내려준다() {
        givenRequest(request(CareRequestStatus.COMPLETED));

        assertThat(service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID).accessUntil()).isEqualTo(ENDS_AT);
    }

    // === 볼 수 있을 때 ===

    @Test
    void 공개_항목이_없으면_볼_수_있지만_빈_목록이고_이력을_남기지_않는다() {
        assertAccessibleAndEmpty();
        verifyNoInteractions(safetyInformationRepository);
    }

    @Test
    void 분류_순서와_작성순으로_묶고_항목이_없는_분류는_뺀다() {
        givenPermissions(
                active(100L, 22L, InformationType.ENTRY_METHOD, ACCEPTED_AT),
                active(101L, 14L, InformationType.ALLERGY, ACCEPTED_AT),
                active(102L, 12L, InformationType.MEDICATION, ACCEPTED_AT));
        givenItems(
                item(22L, REQUESTER_ID, InformationType.ENTRY_METHOD, null, ACCEPTED_AT),
                item(14L, REQUESTER_ID, InformationType.ALLERGY, "페니실린", ACCEPTED_AT),
                item(12L, REQUESTER_ID, InformationType.MEDICATION, "혈압약", ACCEPTED_AT));

        SharedInformationResponse response = service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);

        assertThat(response.accessible()).isTrue();
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.accessUntil()).isEqualTo(ENDS_AT);
        // 기본 정보와 미리 남기는 말은 항목이 없어 빠짐
        assertThat(response.categories())
                .extracting(SharedCategory::category, SharedCategory::categoryName)
                .containsExactly(
                        tuple(InformationCategory.HEALTH, InformationCategory.HEALTH.getDescription()),
                        tuple(InformationCategory.LIFE, InformationCategory.LIFE.getDescription()));
        // 건강 정보 안에서는 작성순(ID 오름차순)
        assertThat(response.categories().get(0).items())
                .extracting(SharedItem::informationId, SharedItem::typeName, SharedItem::title, SharedItem::sensitive)
                .containsExactly(
                        tuple(12L, InformationType.MEDICATION.getDescription(), "혈압약", true),
                        tuple(14L, InformationType.ALLERGY.getDescription(), "페니실린", true));
    }

    @Test
    void 내려준_항목마다_VIEWED_이력을_남긴다() {
        givenPermissions(
                active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT),
                active(101L, 22L, InformationType.ENTRY_METHOD, ACCEPTED_AT));
        givenItems(
                item(12L, REQUESTER_ID, InformationType.MEDICATION, "혈압약", ACCEPTED_AT),
                item(22L, REQUESTER_ID, InformationType.ENTRY_METHOD, null, ACCEPTED_AT));

        service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);

        // 정보 주인 = 요청자, 수행자 = 조회한 담당자, 시각 = 지금
        assertThat(savedLogs())
                .extracting(InformationAccessLog::getPermissionId, InformationAccessLog::getOwnerUserId,
                        InformationAccessLog::getActorUserId, InformationAccessLog::getInformationId,
                        InformationAccessLog::getEventType, InformationAccessLog::getOccurredAt)
                .containsExactly(
                        tuple(100L, REQUESTER_ID, ASSIGNEE_ID, 12L, AccessEventType.VIEWED, NOW),
                        tuple(101L, REQUESTER_ID, ASSIGNEE_ID, 22L, AccessEventType.VIEWED, NOW));
    }

    @Test
    void 원본이_없거나_다른_사람_항목이면_응답과_이력에서_뺀다() {
        givenPermissions(
                active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT),
                active(101L, 13L, InformationType.DISEASE, ACCEPTED_AT),    // 원본 없음 (스냅샷에 없음)
                active(102L, 14L, InformationType.ALLERGY, ACCEPTED_AT));   // 다른 사람 항목 (방어)
        givenItems(
                item(12L, REQUESTER_ID, InformationType.MEDICATION, "혈압약", ACCEPTED_AT),
                item(14L, 99L, InformationType.ALLERGY, "페니실린", ACCEPTED_AT));

        SharedInformationResponse response = service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);

        assertThat(response.categories()).hasSize(1);
        assertThat(response.categories().get(0).items()).extracting(SharedItem::informationId).containsExactly(12L);
        assertThat(savedLogs()).extracting(InformationAccessLog::getInformationId).containsExactly(12L);
    }

    @Test
    void 공유된_뒤에_수정한_항목만_갱신됨이다() {
        LocalDateTime grantedAt = ACCEPTED_AT;
        givenPermissions(
                active(100L, 12L, InformationType.MEDICATION, grantedAt),
                active(101L, 13L, InformationType.DISEASE, grantedAt),
                active(102L, 14L, InformationType.ALLERGY, grantedAt));
        givenItems(
                item(12L, REQUESTER_ID, InformationType.MEDICATION, "혈압약", grantedAt.plusHours(1)),   // 공유 뒤 수정
                item(13L, REQUESTER_ID, InformationType.DISEASE, "고혈압", grantedAt),                  // 같은 시각 (갱신 아님)
                item(14L, REQUESTER_ID, InformationType.ALLERGY, "페니실린", grantedAt.minusDays(1)));  // 공유 전 수정

        assertThat(service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID).categories().get(0).items())
                .extracting(SharedItem::informationId, SharedItem::updated)
                .containsExactly(tuple(12L, true), tuple(13L, false), tuple(14L, false));
    }

    @Test
    void 수락_뒤_같은_지정에서_처음_공유한_항목만_추가됨이다() {
        givenPermissions(
                active(100L, 12L, InformationType.MEDICATION, ACCEPTED_AT),             // 수락과 함께 활성화
                active(101L, 13L, InformationType.DISEASE, ACCEPTED_AT.plusHours(2)),   // 수락 뒤 재확인으로 추가
                revoked(102L, 14L, InformationType.ALLERGY, RevokeReason.OWNER_REVOKED), // 예전에 공유했다가 철회
                active(103L, 14L, InformationType.ALLERGY, ACCEPTED_AT.plusHours(3)));  // 다시 허용 (새 행)
        givenItems(
                item(12L, REQUESTER_ID, InformationType.MEDICATION, "혈압약", ACCEPTED_AT),
                item(13L, REQUESTER_ID, InformationType.DISEASE, "고혈압", ACCEPTED_AT),
                item(14L, REQUESTER_ID, InformationType.ALLERGY, "페니실린", ACCEPTED_AT));

        assertThat(service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID).categories().get(0).items())
                .extracting(SharedItem::informationId, SharedItem::added)
                .containsExactly(tuple(12L, false), tuple(13L, true), tuple(14L, false));
    }

    // === 도우미 ===

    /** 진행 중이거나 끝난 일반 요청 (지금 담당자 = 조회하는 사람) */
    private static SharingRequestInfo request(CareRequestStatus status) {
        return new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, status, RequestType.GENERAL,
                STARTS_AT, ENDS_AT, SITUATION_ID, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL);
    }

    /** 취소와 만료처럼 C가 지정을 마감해 현재 담당자 값이 없는 요청 */
    private static SharingRequestInfo closedRequest(CareRequestStatus status) {
        return new SharingRequestInfo(REQUEST_ID, REQUESTER_ID, null, status, RequestType.GENERAL,
                STARTS_AT, ENDS_AT, SITUATION_ID, null, null, HelpType.HOSPITAL);
    }

    private void givenRequest(SharingRequestInfo request) {
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(request));
    }

    private void givenPermissions(RequestInformationPermission... permissions) {
        when(permissionRepository.findAllByAssignmentIdOrderByIdAsc(ASSIGNMENT_ID)).thenReturn(List.of(permissions));
    }

    /** 원본 조회: 권한이 가리키는 ID 묶음으로 조회되고, 넘긴 항목만 있는 것처럼 돌려줌 */
    @SuppressWarnings("unchecked")
    private void givenItems(SafetyInformation... items) {
        when(safetyInformationRepository.findAllById(any(Iterable.class))).thenReturn(List.of(items));
    }

    /** ACTIVE 권한 (부여 시각을 정해 '추가됨' 판단을 조절) */
    private static RequestInformationPermission active(Long id, Long informationId, InformationType type,
                                                       LocalDateTime grantedAt) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, informationId, type, grantedAt, null);
        ReflectionTestUtils.setField(permission, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return permission;
    }

    /** 끝난 권한 */
    private static RequestInformationPermission revoked(Long id, Long informationId, InformationType type,
                                                        RevokeReason reason) {
        RequestInformationPermission permission = active(id, informationId, type, ACCEPTED_AT);
        permission.revoke(reason, ACCEPTED_AT.plusHours(1));
        return permission;
    }

    /** 항목 삭제로 항목 ID가 NULL이 된 권한 (ERD 9.5 삭제 순서와 같은 결과) */
    private static RequestInformationPermission deletedItem(RequestInformationPermission permission) {
        ReflectionTestUtils.setField(permission, "informationId", null);
        return permission;
    }

    /** 안심카드 항목 원본 (수정 시각을 정해 '갱신됨' 판단을 조절) */
    private static SafetyInformation item(Long id, Long ownerId, InformationType type, String title,
                                          LocalDateTime updatedAt) {
        SafetyInformation item = SafetyInformation.create(ownerId, type, title, "내용");
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "updatedAt", updatedAt); // DB 없이 자동 입력이 없어 직접 넣음
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<InformationAccessLog> savedLogs() {
        ArgumentCaptor<Collection<InformationAccessLog>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(accessLogRepository).saveAll(captor.capture());
        return List.copyOf(captor.getValue());
    }

    private void assertSharedInfoNotFound() {
        assertThatThrownBy(() -> service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARED_INFO_NOT_FOUND);
    }

    private void assertUnavailable(UnavailableReason reason) {
        SharedInformationResponse response = service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(response.accessible()).isFalse();
        assertThat(response.unavailableReason()).isEqualTo(reason);
        assertThat(response.categories()).isEmpty(); // 볼 수 없으면 항목 내용을 내려주지 않음
    }

    private void assertAccessibleAndEmpty() {
        SharedInformationResponse response = service.getSharedInformation(REQUEST_ID, ASSIGNEE_ID);
        assertThat(response.accessible()).isTrue();
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.categories()).isEmpty();
        verify(accessLogRepository, never()).saveAll(any());
    }
}
