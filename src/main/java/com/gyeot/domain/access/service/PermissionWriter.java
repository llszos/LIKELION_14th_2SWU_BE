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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 접근 권한을 바꾸는 작업의 공통 처리입니다. (F-09, ERD 4.11·4.12, #42·#43)
 * 권한 부여(#42)와 회수(#43)가 같은 규칙으로 권한과 접근 이력을 다루도록 한곳에 모았습니다.
 *
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

    private final RequestInformationPermissionRepository permissionRepository; // 권한 조회·저장
    private final InformationAccessLogRepository accessLogRepository;          // 부여·회수 이력 저장
    private final SafetyInformationRepository safetyInformationRepository;     // 공개 항목 존재·주인·유형 확인

    /** 대상 지정 하나의 끝나지 않은 권한(PENDING·ACTIVE)을 조회합니다. */
    public List<RequestInformationPermission> findOpenPermissions(Long assignmentId) {
        return permissionRepository.findAllByAssignmentIdAndStatusIn(assignmentId, OPEN_STATUSES);
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

        // 한 번에 조회해, 개수가 모자라면(없거나 삭제됨) 또는 하나라도 주인이 다르면 전체 거부
        // 일부만 빼고 만들면 요청자가 고른 것과 실제 공개 항목이 달라지므로 전부 아니면 거부
        List<SafetyInformation> found = safetyInformationRepository.findAllById(uniqueIds);
        boolean allOwned = found.size() == uniqueIds.size()
                && found.stream().allMatch(information -> Objects.equals(information.getOwnerId(), ownerUserId));
        if (!allOwned) {
            throw invalidSelection();
        }

        // 권한에 공유 당시 항목 유형을 저장하므로 항목 ID → 유형으로 돌려줌
        return found.stream().collect(Collectors.toMap(SafetyInformation::getId, SafetyInformation::getInformationType));
    }

    /**
     * 대상 지정에 공개 항목마다 공유 예정(PENDING) 권한을 만듭니다. (요청 생성·대상 변경·수락 전 수정 확정)
     * - 항목이 있고 정보 주인의 것인지 먼저 확인합니다. (loadOwnedItemTypes)
     * - 같은 지정에 이미 끝나지 않은 권한(PENDING·ACTIVE)이 있는 항목은 만들지 않습니다.
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

        // 이 지정에 이미 PENDING·ACTIVE로 있는 항목은 건너뜀 (같은 이벤트가 다시 와도 중복 생성 안 함)
        Set<Long> alreadyOpen = openInformationIds(assignmentId);
        // 요청자가 고른 순서대로 항목마다 권한 1행 (권한은 지정·항목 단위)
        List<RequestInformationPermission> created = informationIds.stream()
                .filter(informationId -> !alreadyOpen.contains(informationId))
                .map(informationId -> RequestInformationPermission.createPending(
                        requestId, assignmentId, informationId, types.get(informationId)))
                .toList();

        return permissionRepository.saveAll(created);
    }

    /**
     * 대상 지정의 공유 예정(PENDING) 권한을 모두 활성화(ACTIVE)하고 항목마다 GRANTED 이력을 남깁니다. (수락)
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
     * - PENDING과 ACTIVE 모두 REVOKED로 바꾸고, revoked_at은 이벤트 발생 시각을 씁니다.
     * - ACTIVE였던 권한만 AUTO_REVOKED 이력을 남깁니다. PENDING은 담당자가 본 적이 없어 남기지 않습니다.
     * - 이력 수행자: 공개 항목 제외(SHARING_EXCLUDED)는 정보 주인이 뺀 것이라 정보 주인, 그 밖은 시스템 처리라 NULL
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

        // 공개 항목 제외는 정보 주인이 직접 뺀 것, 그 밖(요청 종료·지정 마감 등)은 시스템 처리라 수행자 없음
        Long actorUserId = reason == RevokeReason.SHARING_EXCLUDED ? ownerUserId : null;
        List<InformationAccessLog> logs = new ArrayList<>();

        for (RequestInformationPermission permission : permissions) {

            if (permission.getStatus() == PermissionStatus.REVOKED) {
                continue; // 이미 끝난 권한은 처음 끝난 이유·시각을 그대로 둠 (같은 이벤트 재처리 대비)
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
     * 대상 지정 하나의 끝나지 않은 권한(PENDING·ACTIVE)을 모두 끝냅니다. (대상 변경, #43 수락 철회·지정 마감)
     * 처리 규칙은 revokeByRequestEvent와 같습니다.
     */
    public void revokeAssignment(Long assignmentId, RevokeReason reason, Long ownerUserId, LocalDateTime revokedAt) {
        revokeByRequestEvent(findOpenPermissions(assignmentId), reason, ownerUserId, revokedAt);
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
