package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 접근 권한을 바꾸는 작업의 공통 처리입니다. (F-09, ERD 4.11, 4.12, #42, #43)
 * 권한 부여(#42)와 회수(#43)가 같은 규칙으로 권한과 접근 이력을 다루도록 한곳에 모았습니다.
 *
 * - 회수는 끝나는 범위에 따라 두 가지로 나뉩니다.
 *   지정 하나만 끝나면 revokeAssignment(대상 변경, 수락 철회, 지정 마감),
 *   요청 자체가 끝나면 revokeRequest(완료, 취소, 만료)를 씁니다. 둘 다 revokeByRequestEvent로 같은 규칙을 적용합니다.
 *   안심카드 항목을 지우면 revokeItem으로 그 항목의 권한만 끝냅니다. (#38)
 *
 * - 잠금 규칙 (#38, 항목 삭제와 권한 변경의 경합 방지)
 *   1. 작업 하나(이벤트 하나, 항목 삭제 하나)에 필요한 항목 ID를 먼저 모읍니다. 권한 엔티티는 읽지 않고 ID 값만 읽습니다.
 *   2. 모은 항목을 lockItems로 한 번에 잠급니다. 메서드마다 따로 잠그면 작업 전체의 잠금 순서가 엇갈릴 수 있습니다.
 *   3. 그 뒤에 권한을 잠금 조회로 읽어 최신 상태로 판단합니다. 항목 확인과 거르기도 잠금 조회 결과로 합니다.
 *   잠그는 쪽은 서비스(PermissionGrantService, PermissionRevokeService, 항목 삭제)이고,
 *   이 클래스는 잠금용 메서드(lockItems, lockItemsOfAssignment, lockItemsOfRequest)와 잠금 조회를 제공합니다.
 * - 호출하는 쪽의 트랜잭션 안에서만 실행합니다. (MANDATORY)
 *   C 요청 이벤트는 C가 요청 행을 잠근 트랜잭션 안에서 동기로 발행하므로, 같은 요청의 권한 변경은 순서대로 처리됩니다.
 * - 권한 변경과 접근 이력 저장은 같은 트랜잭션입니다. 하나라도 실패하면 발행한 C 작업까지 함께 되돌립니다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionWriter {

    // 아직 끝나지 않은 권한 (수락 전 + 수락 후)
    static final List<PermissionStatus> OPEN_STATUSES = List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE);

    private final RequestInformationPermissionRepository permissionRepository; // 권한 조회, 저장
    private final InformationAccessLogRepository accessLogRepository;          // 부여, 회수 이력 저장
    private final SafetyInformationRepository safetyInformationRepository;     // 공개 항목의 존재, 주인, 유형 확인

    /** 대상 지정 하나의 끝나지 않은 권한(PENDING, ACTIVE)을 조회합니다. */
    public List<RequestInformationPermission> findOpenPermissions(Long assignmentId) {
        return permissionRepository.findAllByAssignmentIdAndStatusIn(assignmentId, OPEN_STATUSES);
    }

    /**
     * 안심카드 항목 행을 잠급니다. (#38 잠금 규칙 2단계)
     *
     * - 작업 하나에 필요한 항목 ID를 모두 모아 한 번만 부릅니다. null과 중복은 빼고 ID 오름차순으로 정렬합니다.
     * - 정렬한 ID마다 단건 잠금 조회를 차례로 실행합니다. IN 조회 한 번으로 잠그면 실제 잠금 획득 순서를
     *   DB 실행 계획이 정해, 정렬한 순서대로 잠근다고 보장할 수 없기 때문입니다.
     * - 이미 삭제된 항목은 잠글 행이 없어 그냥 넘어갑니다. 삭제 여부는 뒤의 항목 확인(loadOwnedItemTypes 등)에서 판단합니다.
     * - 항목 삭제도 같은 항목 행을 먼저 잠그므로, 이 잠금을 쥔 동안에는 그 항목이 삭제되지 않습니다.
     */
    public void lockItems(Collection<Long> informationIds) {

        if (informationIds == null || informationIds.isEmpty()) {
            return; // 잠글 항목 없음 (공개 항목 0개, 열린 권한 없음)
        }

        // null 제외, 중복 제거, 오름차순 정렬 (같은 항목들을 잠그는 작업끼리 순서를 맞추려는 것)
        List<Long> sortedIds = informationIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        if (sortedIds.isEmpty()) {
            return;
        }

        // 오름차순으로 하나씩 잠금 (코드 순서 = 잠금 획득 순서)
        // 결과는 쓰지 않음. 잠금을 거는 것이 목적이고, 존재와 소유 판단은 잠근 뒤 다시 조회해서 함
        for (Long informationId : sortedIds) {
            safetyInformationRepository.findByIdForUpdate(informationId);
        }
    }

    /**
     * 대상 지정의 열린 권한이 가리키는 항목과 함께 추가 항목까지 한 번에 잠급니다. (#38 잠금 규칙 1, 2단계)
     *
     * 수락, 지정 회수처럼 기존 권한만 바뀌면 추가 항목 없이, 대상 변경과 수정 확정처럼 새 항목도 공개하면
     * 이벤트의 항목 ID를 함께 넘깁니다. 이전 지정과 새 지정을 한 작업에서 다루면 둘을 모두 넘깁니다.
     *
     * @param assignmentId          열린 권한을 볼 지정 (null이면 그 지정은 건너뜀)
     * @param extraInformationIds   함께 잠글 항목 ID (null 가능)
     */
    public void lockItemsOfAssignment(Long assignmentId, Collection<Long> extraInformationIds) {
        List<Long> ids = new ArrayList<>();

        if (assignmentId != null) {
            // 권한 엔티티가 아니라 항목 ID만 읽음 (잠그기 전에 엔티티를 읽으면 예전 상태가 영속성 컨텍스트에 남음)
            ids.addAll(permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(assignmentId, OPEN_STATUSES));
        }
        if (extraInformationIds != null) {
            ids.addAll(extraInformationIds);
        }

        lockItems(ids);
    }

    /**
     * 요청의 열린 권한이 가리키는 항목을 모두 잠급니다. (#38 잠금 규칙 1, 2단계, 요청 종료 시 회수)
     *
     * 지정과 상관없이 그 요청의 열린 권한 전체가 대상입니다. (revokeRequest와 같은 범위)
     */
    public void lockItemsOfRequest(Long requestId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");

        // 항목 ID만 읽음 (lockItemsOfAssignment와 같은 이유)
        lockItems(permissionRepository.findInformationIdsByRequestIdAndStatusIn(requestId, OPEN_STATUSES));
    }

    /**
     * 공개할 항목이 모두 있고 정보 주인(요청자)의 것인지 확인하고, 항목 ID별 유형을 돌려줍니다.
     * 권한에는 공유 당시 항목 유형을 저장하므로 확인과 유형 조회를 한 번의 조회로 처리합니다.
     *
     * C가 요청을 저장하기 전에 SafetyInformationSharingReader로 이미 확인하지만,
     * 그 사이 항목이 삭제될 수 있어 권한을 만들기 직전에 D 데이터로 한 번 더 확인합니다.
     * 지정이 그 요청에 속하는지는 C가 이벤트를 만들 때 보장하므로 여기서 확인하지 않습니다.
     *
     * - 빈 목록은 통과합니다. (공개 항목 0개 요청 허용)
     * - 없는 ID, 다른 사람의 ID, null, 중복 ID가 있으면 전체를 거부합니다. (C 요청 API가 400을 그대로 반환)
     */
    public Map<Long, InformationType> loadOwnedItemTypes(Long ownerUserId, List<Long> informationIds) {

        if (informationIds == null || informationIds.isEmpty()) {
            return Map.of(); // 공개 항목 0개 요청 허용 (조회하지 않음)
        }

        // null이나 같은 ID가 두 번 들어오면 잘못 보낸 것이므로 거부 (Set 크기가 원래 목록보다 작으면 중복)
        Set<Long> uniqueIds = new LinkedHashSet<>(informationIds);
        if (uniqueIds.contains(null) || uniqueIds.size() != informationIds.size()) {
            throw invalidSelection();
        }

        // 한 번에 잠금 조회해, 개수가 모자라면(없거나 삭제됨) 또는 하나라도 주인이 다르면 전체 거부
        // 일부만 빼고 만들면 요청자가 고른 것과 실제 공개 항목이 달라지므로 전부 아니면 거부
        // 잠금 조회라 최신 상태를 보고, 서비스가 lockItems로 미리 잠갔다면 기다리지 않고 바로 돌려줌 (#38 잠금 규칙 3단계)
        // 잠금 순서는 lockItems에서 이미 정해졌고, 여기서는 확보한 항목을 확인하는 용도
        List<SafetyInformation> found = safetyInformationRepository.findAllByIdInForUpdate(uniqueIds);
        boolean allOwned = found.size() == uniqueIds.size()
                && found.stream().allMatch(information -> Objects.equals(information.getOwnerId(), ownerUserId));
        if (!allOwned) {
            throw invalidSelection();
        }

        // 권한에 공유 당시 항목 유형을 저장하므로 항목 ID → 유형으로 돌려줌
        return found.stream().collect(Collectors.toMap(SafetyInformation::getId, SafetyInformation::getInformationType));
    }

    /**
     * 공개하려는 항목 중 지금 존재하고 정보 주인(요청자)의 것만 골라 돌려줍니다. (재확인 수락, ERD 4.8 변경안 적용 방식)
     *
     * loadOwnedItemTypes와 달리 거부하지 않고 걸러 냅니다.
     * 재확인을 기다리는 동안 요청자가 항목을 삭제할 수 있는데, 담당자는 항목을 고칠 수 없어
     * 전체를 거부하면 재확인 기한이 끝날 때까지 수락할 방법이 없기 때문입니다.
     *
     * - 요청자가 고른 순서를 유지하고, null과 중복 ID는 뺍니다.
     * - 빈 목록이면 조회하지 않고 빈 목록을 돌려줍니다.
     * - 걸러서 남은 항목이 하나도 없으면 빈 목록입니다. 이때 수정 확정은 공개 중인 항목을 모두 회수합니다.
     *
     * 실제 서비스에서는 항목을 삭제할 때(#38) 그 항목의 권한이 먼저 회수되므로,
     * 여기서 걸러지는 것은 주로 아직 권한이 없던 추가 예정 항목입니다.
     */
    public List<Long> filterOwnedItemIds(Long ownerUserId, List<Long> informationIds) {

        if (informationIds == null || informationIds.isEmpty()) {
            return List.of();
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(informationIds); // 순서 유지하며 중복 제거
        uniqueIds.remove(null); // 잘못 들어온 null은 조회 대상에서 뺌

        // 한 번에 잠금 조회해 지금 있고 요청자 것인 항목 ID만 모음 (삭제된 항목은 조회되지 않음)
        // 일반 조회로 거른 뒤 잠그면 그 사이 삭제가 끼어들 수 있어, 거르는 조회 자체를 잠금 조회로 함 (#38 잠금 규칙 3단계)
        Set<Long> ownedIds = new HashSet<>();
        for (SafetyInformation information : safetyInformationRepository.findAllByIdInForUpdate(uniqueIds)) {
            if (Objects.equals(information.getOwnerId(), ownerUserId)) {
                ownedIds.add(information.getId());
            }
        }

        return uniqueIds.stream().filter(ownedIds::contains).toList(); // 요청자가 고른 순서대로 돌려줌
    }

    /**
     * 대상 지정에 공개 항목마다 공유 예정(PENDING) 권한을 만듭니다. (요청 생성, 대상 변경, 수락 전 수정 확정)
     *
     * - 항목이 있고 정보 주인의 것인지 먼저 확인합니다. (loadOwnedItemTypes)
     * - 같은 지정에 이미 끝나지 않은 권한(PENDING, ACTIVE)이 있는 항목은 만들지 않습니다.
     *   같은 이벤트가 다시 와도 권한이 중복되지 않습니다. 회수된(REVOKED) 행은 보존하고 새 행을 만듭니다.
     * - 공개 항목이 0개면 아무것도 만들지 않습니다.
     *
     * @return 새로 만든 권한
     */
    public List<RequestInformationPermission> createPending(Long requestId, Long assignmentId,
                                                            Long ownerUserId, List<Long> informationIds) {

        Map<Long, InformationType> types = loadOwnedItemTypes(ownerUserId, informationIds); // 확인 실패 시 여기서 예외
        if (types.isEmpty()) {
            return List.of(); // 공개 항목 0개
        }

        // 이 지정에 이미 PENDING이나 ACTIVE로 있는 항목은 건너뜀 (같은 이벤트가 다시 와도 중복 생성 안 함)
        Set<Long> alreadyOpen = openInformationIds(assignmentId);

        // 요청자가 고른 순서대로 항목마다 권한 1행 (권한은 지정과 항목 단위)
        List<RequestInformationPermission> created = informationIds.stream()
                .filter(informationId -> !alreadyOpen.contains(informationId))
                .map(informationId -> RequestInformationPermission.createPending(
                        requestId, assignmentId, informationId, types.get(informationId)))
                .toList();

        return permissionRepository.saveAll(created);
    }

    /**
     * 대상 지정에 공개 항목마다 바로 활성화된(ACTIVE) 권한을 만들고 GRANTED 이력을 남깁니다. (수락 후 수정 확정으로 항목 추가)
     *
     * - 항목 확인과 중복 방지 규칙은 createPending과 같습니다.
     * - 회수됐던 항목을 다시 넣으면 기존 REVOKED 행은 그대로 두고 새 행을 만듭니다.
     *
     * @param ownerUserId 정보 주인 ID (요청자 ID)
     * @param actorUserId 권한이 열리게 한 사람 (재확인을 수락한 담당자)
     * @param expiresAt   급한 요청만 값이 있고, 일반 요청은 null
     * @return 새로 만든 권한
     */
    public List<RequestInformationPermission> createActive(Long requestId, Long assignmentId, Long ownerUserId,
                                                           Long actorUserId, List<Long> informationIds,
                                                           LocalDateTime grantedAt, LocalDateTime expiresAt) {

        Map<Long, InformationType> types = loadOwnedItemTypes(ownerUserId, informationIds); // 확인 실패 시 여기서 예외
        if (types.isEmpty()) {
            return List.of();
        }

        Set<Long> alreadyOpen = openInformationIds(assignmentId); // 이미 PENDING이나 ACTIVE인 항목은 건너뜀
        List<RequestInformationPermission> created = permissionRepository.saveAll(informationIds.stream()
                .filter(informationId -> !alreadyOpen.contains(informationId))
                .map(informationId -> RequestInformationPermission.createActive(requestId, assignmentId,
                        informationId, types.get(informationId), grantedAt, expiresAt))
                .toList());

        // 이력에 권한 ID가 들어가야 하므로 저장한 뒤에 만듦
        accessLogRepository.saveAll(created.stream()
                .map(permission -> grantedLog(permission, ownerUserId, actorUserId, grantedAt))
                .toList());
        return created;
    }

    /**
     * 대상 지정의 공유 예정(PENDING) 권한을 모두 활성화(ACTIVE)하고 항목마다 GRANTED 이력을 남깁니다. (수락)
     *
     * - PENDING만 바꿉니다. 이미 ACTIVE인 권한은 그대로 두어, 같은 수락 이벤트가 다시 와도 이력이 늘거나 기한이 연장되지 않습니다.
     * - granted_at은 이벤트 발생 시각입니다.
     *
     * @param ownerUserId 정보 주인 ID (요청자 ID, 이력의 owner_user_id)
     * @param actorUserId 권한이 열리게 한 사람 (수락이면 담당자)
     * @param expiresAt   급한 요청만 값이 있고, 일반 요청은 null (요청 종료 시각으로 판단)
     * @return 이번에 활성화한 권한
     */
    public List<RequestInformationPermission> activatePending(Long assignmentId, Long ownerUserId, Long actorUserId,
                                                              LocalDateTime grantedAt, LocalDateTime expiresAt) {
        Objects.requireNonNull(ownerUserId, "ownerUserId는 필수입니다.");
        Objects.requireNonNull(grantedAt, "grantedAt은 필수입니다.");

        List<RequestInformationPermission> activated = new ArrayList<>();
        List<InformationAccessLog> logs = new ArrayList<>();

        for (RequestInformationPermission permission : findOpenPermissions(assignmentId)) {
            if (permission.getStatus() != PermissionStatus.PENDING) {
                continue; // 이미 ACTIVE면 그대로 (중복 수락 이벤트로 이력이 늘거나 기한이 연장되지 않게)
            }
            permission.activate(grantedAt, expiresAt); // 이 트랜잭션에서 조회한 엔티티라 변경 감지로 저장됨
            activated.add(permission);
            logs.add(grantedLog(permission, ownerUserId, actorUserId, grantedAt)); // 항목마다 GRANTED 1건
        }

        accessLogRepository.saveAll(logs); // 활성화할 게 없으면 빈 목록 저장 (쿼리 없음)
        return activated;
    }

    /**
     * C 요청 이벤트로 권한을 끝냅니다. (회수 공통 처리, #43에서 재사용)
     *
     * - PENDING과 ACTIVE 모두 REVOKED로 바꾸고, revoked_at은 이벤트 발생 시각을 씁니다.
     * - ACTIVE였던 권한만 AUTO_REVOKED 이력을 남깁니다. PENDING은 담당자가 본 적이 없어 남기지 않습니다.
     * - 이력 수행자: 공개 항목 제외(SHARING_EXCLUDED)와 항목 삭제(ITEM_DELETED)는 정보 주인이 직접 한 것이라 정보 주인,
     *   그 밖은 시스템 처리라 NULL (D 요구사항명세 F-10, ERD 4.12)
     * - 이미 REVOKED인 권한은 건너뜁니다. (같은 이벤트가 다시 와도 결과와 이력이 같음)
     * - C 이벤트로 끝난 권한은 C가 알림을 보내므로 PermissionRevokedEvent를 발행하지 않습니다.
     *
     * @param permissions 끝낼 권한 (이 트랜잭션에서 조회한 엔티티, 변경 감지로 저장됨)
     * @param reason      끝난 이유
     * @param ownerUserId 정보 주인 ID (요청자 ID, 이력의 owner_user_id)
     * @param revokedAt   이벤트 발생 시각
     */
    public void revokeByRequestEvent(List<RequestInformationPermission> permissions, RevokeReason reason,
                                     Long ownerUserId, LocalDateTime revokedAt) {
        Objects.requireNonNull(reason, "reason은 필수입니다.");
        Objects.requireNonNull(ownerUserId, "ownerUserId는 필수입니다.");
        Objects.requireNonNull(revokedAt, "revokedAt은 필수입니다.");

        // 공개 항목 제외와 항목 삭제는 정보 주인이 직접 한 것, 그 밖(요청 종료, 지정 마감 등)은 시스템 처리라 수행자 없음
        boolean ownerAction = reason == RevokeReason.SHARING_EXCLUDED || reason == RevokeReason.ITEM_DELETED;
        Long actorUserId = ownerAction ? ownerUserId : null;
        List<InformationAccessLog> logs = new ArrayList<>();

        for (RequestInformationPermission permission : permissions) {

            if (permission.getStatus() == PermissionStatus.REVOKED) {
                continue; // 이미 끝난 권한은 처음 끝난 이유와 시각을 그대로 둠 (같은 이벤트 재처리 대비)
            }

            boolean wasActive = permission.isActive(); // 바꾸기 전에 확인해야 이력 대상인지 알 수 있음
            permission.revoke(reason, revokedAt);

            if (wasActive) { // PENDING은 담당자가 본 적이 없어 접근 이력이 아님
                logs.add(InformationAccessLog.create(permission.getId(), ownerUserId, actorUserId,
                        permission.getInformationId(), permission.getInformationType(),
                        AccessEventType.AUTO_REVOKED, revokedAt));
            }
        }

        accessLogRepository.saveAll(logs);
    }

    /**
     * 대상 지정 하나의 끝나지 않은 권한(PENDING, ACTIVE)을 모두 끝냅니다. (대상 변경, #43 수락 철회와 지정 마감)
     *
     * 처리 규칙은 revokeByRequestEvent와 같습니다.
     */
    public void revokeAssignment(Long assignmentId, RevokeReason reason, Long ownerUserId, LocalDateTime revokedAt) {
        revokeByRequestEvent(findOpenPermissions(assignmentId), reason, ownerUserId, revokedAt);
    }

    /**
     * 요청 하나의 끝나지 않은 권한(PENDING, ACTIVE)을 지정과 상관없이 모두 끝냅니다. (#43 완료, 취소, 만료)
     *
     * - 요청 자체가 끝나면 어느 지정의 권한이든 더 열려 있으면 안 되므로, 지정이 아닌 요청 기준으로 찾습니다.
     * - 취소, 만료 이벤트는 열린 지정이 없을 때 assignmentId가 NULL이라 지정 기준으로는 찾을 수 없습니다.
     * - 처리 규칙(이력, 수행자, 이미 끝난 권한 건너뛰기)은 revokeByRequestEvent와 같습니다.
     *
     * @param requestId   끝난 요청 ID
     * @param reason      끝난 이유 (요청 종료라 보통 REQUEST_ENDED)
     * @param ownerUserId 정보 주인 ID (요청자 ID, 이력의 owner_user_id)
     * @param revokedAt   이벤트 발생 시각
     */
    public void revokeRequest(Long requestId, RevokeReason reason, Long ownerUserId, LocalDateTime revokedAt) {

        // reason, ownerUserId, revokedAt은 revokeByRequestEvent에서 확인하므로 여기서는 requestId만 확인
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");

        // 이전 대상(대상 변경 전 지정)의 권한도 함께 찾음
        // 대상 변경 때 이미 회수했다면 REVOKED라 조회되지 않고, 남아 있는 것만 끝냄
        List<RequestInformationPermission> openPermissions =
                permissionRepository.findAllByRequestIdAndStatusIn(requestId, OPEN_STATUSES);

        // 열린 권한이 없으면(이미 모두 회수됨, 같은 이벤트 재처리) 빈 목록이라 아무것도 바뀌지 않음
        revokeByRequestEvent(openPermissions, reason, ownerUserId, revokedAt);
    }

    /**
     * 안심카드 항목 하나의 끝나지 않은 권한(PENDING, ACTIVE)을 모두 ITEM_DELETED로 끝냅니다. (#38 항목 삭제)
     *
     * - 그 항목이 공개된 모든 요청의 권한이 대상입니다.
     * - 처리 규칙(이력, 이미 끝난 권한 건너뛰기)은 revokeByRequestEvent와 같고, 이력 수행자는 정보 주인입니다.
     * - 회수 전에 ACTIVE였던 권한을 돌려줍니다. 담당자에게 알릴 PermissionRevokedEvent를 만드는 데 씁니다.
     *   PENDING은 담당자가 본 적이 없어 알리지 않으므로 돌려주지 않습니다.
     *
     * @param informationId 삭제할 항목 ID
     * @param ownerUserId   정보 주인 ID (이력의 owner_user_id와 수행자)
     * @param revokedAt     삭제 시각
     * @return 회수 전에 ACTIVE였던 권한 (회수 후 상태)
     */
    public List<RequestInformationPermission> revokeItem(Long informationId, Long ownerUserId, LocalDateTime revokedAt) {

        // ownerUserId, revokedAt은 revokeByRequestEvent에서 확인하므로 여기서는 informationId만 확인
        Objects.requireNonNull(informationId, "informationId는 필수입니다.");

        // 이 항목이 공개된 모든 요청의 열린 권한 (이미 회수된 권한은 조회되지 않아 처음 끝난 이유가 유지됨)
        List<RequestInformationPermission> openPermissions =
                permissionRepository.findAllByInformationIdAndStatusIn(informationId, OPEN_STATUSES);

        // 회수하면 모두 REVOKED가 되므로, 바꾸기 전에 ACTIVE였던 것을 먼저 골라 둠
        List<RequestInformationPermission> wasActive = openPermissions.stream()
                .filter(RequestInformationPermission::isActive)
                .toList();

        // 사유는 항목 삭제로 고정, 수행자는 정보 주인으로 기록됨 (revokeByRequestEvent의 수행자 규칙)
        revokeByRequestEvent(openPermissions, RevokeReason.ITEM_DELETED, ownerUserId, revokedAt);

        // 돌려주는 엔티티는 이미 REVOKED로 바뀐 상태 (요청 ID, 지정 ID만 쓰면 됨)
        return wasActive;
    }

    /**
     * 삭제되는 항목을 가리키던 모든 권한(이미 회수된 과거 권한 포함)의 information_id를 NULL로 바꿉니다. (#38, ERD 9.5)
     *
     * revokeItem 다음에 부릅니다. 방금 회수한 상태 변경을 먼저 DB에 반영한 뒤 바꿉니다. (저장소의 flushAutomatically)
     *
     * @return 바뀐 권한 행 수
     */
    public int clearInformationId(Long informationId) {
        // null로 실행하면 where 조건이 어떤 행과도 맞지 않아 조용히 넘어가므로, 실수를 바로 드러내려고 막음
        Objects.requireNonNull(informationId, "informationId는 필수입니다.");
        return permissionRepository.clearInformationId(informationId);
    }

    private InformationAccessLog grantedLog(RequestInformationPermission permission, Long ownerUserId,
                                            Long actorUserId, LocalDateTime grantedAt) {

        return InformationAccessLog.create(permission.getId(), ownerUserId, actorUserId,
                permission.getInformationId(), permission.getInformationType(), AccessEventType.GRANTED, grantedAt);
    }

    /** 지정에 이미 끝나지 않은 권한이 있는 항목 ID (중복 생성 방지) */
    private Set<Long> openInformationIds(Long assignmentId) {

        Set<Long> ids = new HashSet<>(); // 포함 여부만 보면 되므로 Set

        for (RequestInformationPermission permission : findOpenPermissions(assignmentId)) {
            ids.add(permission.getInformationId());
        }
        return ids;
    }

    private BusinessException invalidSelection() {
        return new BusinessException(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
    }
}
