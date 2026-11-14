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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 담당자가 요청에서 공유받은 안심카드 항목을 조회합니다. (노션 D 파트 API 명세 '전달받은 정보 조회', F-09, F-10, #45)
 *
 * 처리 순서
 * 1. 이 요청의 대상으로 지정된 적이 없는 사람(요청자 본인, 없는 요청 포함)은 404로 막습니다. (존재 여부 비노출)
 * 2. 지금 볼 수 없으면 200 + accessible=false와 이유를 내려줍니다. 앞에서부터 확인해 처음 걸린 이유를 씁니다.
 *    - 요청 상태 → 종료 시각 → 지금 담당자인지 → 관계 → 상황 참여 → 정보 주인의 직접 철회
 * 3. 볼 수 있으면 ACTIVE 권한의 항목을 분류별로 묶어 내려주고, 내려준 항목마다 VIEWED 이력을 남깁니다.
 *
 * - 스케줄러가 늦어도 정보가 새지 않도록 권한이 ACTIVE라는 것만 믿지 않고 조회할 때마다 모두 확인합니다. (ERD 4.11, 8.3)
 * - 권한을 바꾸지 않는 조회라 잠그지 않습니다. 권한을 읽은 뒤 항목이 삭제되면 그 항목은 응답과 이력에서 빠집니다.
 * - 이력을 저장하므로 GET이지만 쓰기 트랜잭션입니다. 이력 저장이 실패하면 응답도 실패합니다. (이력 없이 내용을 내려주지 않음)
 * - 급한 요청(F-13)은 SHOULD 범위라 수락 후 24시간 기한(expires_at)과 accessUntil 계산을 하지 않습니다. (일반 요청만 대상)
 */
@Service                  // Spring이 객체로 만들어 컨트롤러에 주입
@RequiredArgsConstructor  // 아래 final 필드를 채우는 생성자 자동 생성
public class SharedInformationService {

    private final CareRequestSharingReader careRequestSharingReader;          // C: 요청 상태, 현재 담당자, 지정 이력
    private final RelationshipQueryRepository relationshipQueryRepository;    // A: 정보 주인과 담당자의 관계
    private final SituationParticipantReader situationParticipantReader;      // B: 담당자의 상황 참여 여부
    private final RequestInformationPermissionRepository permissionRepository; // 이 지정의 권한
    private final SafetyInformationRepository safetyInformationRepository;    // 공유된 항목 원본
    private final InformationAccessLogRepository accessLogRepository;         // VIEWED 이력 저장
    private final Clock clock;                                                // 지금 시각 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

    @Transactional // VIEWED 이력을 저장하므로 읽기 전용이 아님
    public SharedInformationResponse getSharedInformation(Long requestId, Long userId) {

        // 1. 이 요청의 대상이었던 적이 없으면 404 (지난 지정, 수락 전 지정 포함. 공개 항목 0개 요청도 지정 행으로 확인)
        //    요청 정보보다 먼저 확인해, 무관한 사람에게는 요청이 있는지 없는지도 알려 주지 않음 (없는 요청도 같은 404)
        if (!careRequestSharingReader.hasBeenAssigned(requestId, userId)) {
            throw sharedInfoNotFound();
        }
        SharingRequestInfo request = careRequestSharingReader.findById(requestId)
                .orElseThrow(this::sharedInfoNotFound); // 위 확인 직후 요청이 지워진 경우 (방어)

        LocalDateTime now = LocalDateTime.now(clock); // 종료 시각 비교와 VIEWED 이력 시각에 같은 값을 씀
        LocalDateTime accessUntil = request.endsAt(); // 일반 요청은 요청 종료 시각. 급한 요청은 null (SHOULD 범위)

        // 지금 담당자일 때만 이 지정의 권한을 읽음 (지난 담당자는 어차피 ASSIGNMENT_CLOSED라 읽을 필요 없음)
        boolean currentAssignee = isCurrentAssignee(request, userId);
        List<RequestInformationPermission> permissions = currentAssignee
                ? permissionRepository.findAllByAssignmentIdOrderByIdAsc(request.assignmentId())
                : List.of();

        // 2. 볼 수 없으면 이유만 내려줌 (항목 내용과 이력 없음)
        UnavailableReason reason = findUnavailableReason(request, userId, currentAssignee, permissions, now);
        if (reason != null) {
            return SharedInformationResponse.unavailable(requestId, accessUntil, reason);
        }

        // 3. 볼 수 있으면 ACTIVE 권한의 항목을 내려주고 VIEWED 이력 기록
        return SharedInformationResponse.accessible(requestId, accessUntil,
                loadAndRecordView(request, userId, permissions, now));
    }

    // === 볼 수 없는 이유 ===

    /**
     * 지금 볼 수 없는 이유를 돌려줍니다. 볼 수 있으면 null입니다.
     *
     * - 요청 상태부터 봅니다. 끝난 요청은 담당자가 남아 있어도(완료) 볼 수 없습니다.
     * - 수락됨, 진행 중이어도 종료 시각이 지났으면 볼 수 없습니다. 스케줄러가 완료로 바꾸기 전(최대 1분)에도 막기 위함입니다.
     * - 그다음 지금 담당자인지, 관계와 상황 참여가 유효한지 확인합니다. C가 관계 해제와 상황 나가기 때 지정을 마감하지만,
     *   그 처리 전후에도 정보가 새지 않도록 여기서 다시 확인합니다.
     * - 마지막으로 ACTIVE 권한이 하나도 없는데 정보 주인이 직접 철회한 권한이 있으면 OWNER_REVOKED입니다.
     *   공개 항목이 0개이거나, 항목 삭제와 공개 항목 제외로 모두 빠진 경우는 볼 수 있는 상태(빈 목록)로 둡니다. (명세)
     */
    private UnavailableReason findUnavailableReason(SharingRequestInfo request, Long userId, boolean currentAssignee,
                                                    List<RequestInformationPermission> permissions, LocalDateTime now) {
        // 요청 상태 6개를 모두 다룸. 끝난 상태(완료, 취소, 만료)와 수락 전 상태는 바로 이유를 돌려주고,
        // 볼 수 있는 상태(수락됨, 진행 중)만 아래 확인으로 넘어감
        switch (request.status()) {
            case REQUESTED:
                // TODO: C의 isCurrentTarget(requestId, userId)가 들어오면, 지금 대상이 아닌 예전 대상(거절, 수락 철회 후
                //       다른 사람에게 넘어감)은 ASSIGNMENT_CLOSED로 구분합니다. 그 전까지는 모두 NOT_ACCEPTED (유빈 님께 요청)
                return UnavailableReason.NOT_ACCEPTED;
            case COMPLETED:
                return UnavailableReason.REQUEST_COMPLETED; // 사용자 완료와 스케줄러 자동 완료 모두 (FE는 같은 '완료' 문구)
            case CANCELED:
                return UnavailableReason.REQUEST_CANCELED;
            case EXPIRED:
                return UnavailableReason.REQUEST_EXPIRED;
            case ACCEPTED, IN_PROGRESS:
                break; // 아래에서 기한, 담당자, 관계, 상황, 권한을 확인
        }

        if (request.endsAt() != null && !now.isBefore(request.endsAt())) {
            return UnavailableReason.REQUEST_ENDED_BY_TIME; // 종료 시각이 됐지만 아직 완료로 바뀌기 전
        }
        if (!currentAssignee) {
            return UnavailableReason.ASSIGNMENT_CLOSED; // 수락 철회, 대상 변경 등으로 지금 담당자가 아님
        }
        // 관계는 정보 주인(요청자) → 담당자 방향 (나의 사람 해제는 해제한 방향만 회수, F-30 방향 규칙)
        if (!relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                request.requesterId(), userId, RelationshipStatus.ACTIVE)) {
            return UnavailableReason.ASSIGNMENT_CLOSED;
        }
        // 일반 요청은 상황 안에서 만들어지므로 담당자가 상황에 참여 중이어야 함 (급한 요청은 상황이 없음)
        if (request.requestType() == RequestType.GENERAL && request.situationId() != null
                && !situationParticipantReader.isActiveParticipant(request.situationId(), userId)) {
            return UnavailableReason.ASSIGNMENT_CLOSED;
        }

        boolean anyActive = permissions.stream().anyMatch(RequestInformationPermission::isActive);   // 지금 볼 수 있는 권한이 있는지
        boolean ownerRevoked = permissions.stream()                                                // 정보 주인이 카드를 직접 철회한 적이 있는지
                .anyMatch(permission -> permission.getRevokeReason() == RevokeReason.OWNER_REVOKED);
        if (!anyActive && ownerRevoked) {
            return UnavailableReason.OWNER_REVOKED; // 다시 허용하면 새 ACTIVE 권한이 생겨 다시 볼 수 있음
        }
        return null;
    }

    /** 지금 담당자인지: 요청에 수락했고 마감되지 않은 지정이 있고, 그 담당자가 나인지 */
    private static boolean isCurrentAssignee(SharingRequestInfo request, Long userId) {
        return request.assignmentId() != null                      // 수락한 담당자가 없으면(수락 전, 취소, 만료) null
                && Objects.equals(request.assigneeId(), userId);   // null이어도 예외 없이 비교
    }

    // === 볼 수 있을 때: 항목과 이력 ===

    /**
     * ACTIVE 권한의 항목을 분류별로 묶고, 내려준 항목마다 VIEWED 이력을 남깁니다.
     *
     * - 항목 원본이 남아 있고 정보 주인이 요청자인 항목만 내려줍니다. (권한 ID만으로 허용하지 않음, ERD 8.3)
     * - 분류는 기본, 건강, 생활, 미리 남기는 말 순서이고 항목이 없는 분류는 뺍니다. 항목은 작성순(ID 오름차순)입니다.
     */
    private List<SharedCategory> loadAndRecordView(SharingRequestInfo request, Long userId,
                                                   List<RequestInformationPermission> permissions, LocalDateTime now) {

        // 항목 ID → 그 항목의 ACTIVE 권한 (한 지정에서 같은 항목의 ACTIVE 권한은 하나뿐, 혹시 겹치면 먼저 만든 권한)
        Map<Long, RequestInformationPermission> activeByInformationId = permissions.stream()
                .filter(RequestInformationPermission::isActive)
                .filter(permission -> permission.getInformationId() != null) // 삭제된 항목의 권한은 항목 ID가 NULL (ERD 9.5)
                .collect(Collectors.toMap(RequestInformationPermission::getInformationId, Function.identity(),
                        (first, second) -> first));

        if (activeByInformationId.isEmpty()) {
            return List.of(); // 공개 항목 0개, 또는 삭제와 제외로 모두 빠짐 → 볼 수 있지만 빈 목록 (이력 없음)
        }

        // 원본 조회. 권한을 읽은 뒤 삭제된 항목은 결과에 없어 자연히 빠짐
        List<SafetyInformation> items = safetyInformationRepository.findAllById(activeByInformationId.keySet()).stream()
                .filter(item -> Objects.equals(item.getOwnerId(), request.requesterId())) // 정보 주인 = 요청자 (방어)
                .sorted(Comparator.comparing(SafetyInformation::getId))                  // 작성순
                .toList();

        Map<InformationCategory, List<SharedItem>> itemsByCategory = new EnumMap<>(InformationCategory.class); // 키를 enum 선언 순서로 유지
        List<InformationAccessLog> viewLogs = new ArrayList<>();

        for (SafetyInformation item : items) {
            RequestInformationPermission permission = activeByInformationId.get(item.getId());
            InformationType type = item.getInformationType();

            itemsByCategory.computeIfAbsent(type.getCategory(), category -> new ArrayList<>()) // 처음 나온 분류면 빈 목록부터
                    .add(new SharedItem(
                            item.getId(),
                            type,
                            type.getDescription(),                         // 유형 이름 (응답의 typeName)
                            item.getTitle(),                               // 조회할 때 복호화됨 (암호화 컬럼)
                            item.getInformationValue(),                    // 조회할 때 복호화됨 (암호화 컬럼)
                            item.getUpdatedAt(),                           // 작성 후 수정하지 않았으면 작성 시각
                            isUpdated(item, permission),                   // '갱신됨'
                            isAdded(permission, permissions, request),     // '추가됨'
                            type.isSensitive()));                          // '민감'

            // 내려준 항목마다 1건 (정보 주인 = 요청자, 수행자 = 조회한 담당자, F-10)
            viewLogs.add(InformationAccessLog.create(permission.getId(), request.requesterId(), userId,
                    item.getId(), type, AccessEventType.VIEWED, now));
        }
        accessLogRepository.saveAll(viewLogs); // 항목마다 따로 저장하지 않고 한 번에 저장

        // EnumMap은 키를 enum 선언 순서(기본, 건강, 생활, 미리 남기는 말)로 돌려주고, 항목이 없는 분류는 키가 없어 빠짐
        return itemsByCategory.entrySet().stream()
                .map(entry -> new SharedCategory(entry.getKey(), entry.getKey().getDescription(), entry.getValue()))
                .toList();
    }

    /**
     * '갱신됨': 공유된(ACTIVE가 된) 뒤에 정보 주인이 항목을 수정함 (항목 수정 시각 > 권한 부여 시각, F-02)
     * 같은 시각이면 공유와 동시라 갱신으로 보지 않습니다.
     */
    private static boolean isUpdated(SafetyInformation item, RequestInformationPermission permission) {
        return item.getUpdatedAt() != null && permission.getGrantedAt() != null // ACTIVE 권한은 부여 시각이 항상 있지만 방어
                && item.getUpdatedAt().isAfter(permission.getGrantedAt());
    }

    /**
     * '추가됨': 담당자 수락 뒤에 처음 공유된 항목
     *
     * - 수락 때 PENDING에서 ACTIVE가 된 권한은 부여 시각이 수락 시각과 같아 false입니다.
     *   수락 뒤 재확인 수락으로 추가된 권한은 부여 시각이 수락 시각보다 뒤라 true입니다.
     * - 같은 지정에 그 항목의 앞선 권한이 있으면 false입니다. 직접 철회 후 다시 허용하면 새 권한이 생기지만
     *   원래 공유하던 항목이라 '추가됨'으로 보지 않습니다. (같은 지정에서 처음 공유한 항목만, D 요구사항명세)
     * - 수락 시각(C 지정의 accepted_at)과 수락 때 권한의 부여 시각(수락 이벤트의 occurredAt)이 같은 값이라는 전제입니다.
     *   C 수락 서비스(#87)가 같은 시각을 쓰는지 테스트로 확인합니다.
     */
    private static boolean isAdded(RequestInformationPermission permission, List<RequestInformationPermission> permissions,
                                   SharingRequestInfo request) {

        // ① 수락 뒤에 부여된 권한인지 (수락과 함께 활성화된 권한은 여기서 false)
        if (request.acceptedAt() == null || permission.getGrantedAt() == null
                || !permission.getGrantedAt().isAfter(request.acceptedAt())) {
            return false;
        }
        // ② 같은 지정에서 그 항목의 첫 권한인지 (앞선 권한이 끝났어도 있으면 다시 허용으로 보고 false)
        return permissions.stream()
                .noneMatch(earlier -> earlier.getId() < permission.getId()
                        && Objects.equals(earlier.getInformationId(), permission.getInformationId()));
    }

    /** 이 요청과 무관한 사람에게 주는 404 (요청이 없을 때도 같은 응답이라 존재 여부를 알 수 없음) */
    private BusinessException sharedInfoNotFound() {
        return new BusinessException(SafetyCardErrorCode.SHARED_INFO_NOT_FOUND);
    }
}
