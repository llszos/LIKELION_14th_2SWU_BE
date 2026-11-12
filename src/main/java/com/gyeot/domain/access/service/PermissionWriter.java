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

    private final RequestInformationPermissionRepository permissionRepository;
    private final InformationAccessLogRepository accessLogRepository;
    private final SafetyInformationRepository safetyInformationRepository;

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
            return Map.of();
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(informationIds);
        if (uniqueIds.contains(null) || uniqueIds.size() != informationIds.size()) {
            throw invalidSelection();
        }

        List<SafetyInformation> found = safetyInformationRepository.findAllById(uniqueIds);
        boolean allOwned = found.size() == uniqueIds.size()
                && found.stream().allMatch(information -> Objects.equals(information.getOwnerId(), ownerUserId));
        if (!allOwned) {
            throw invalidSelection();
        }

        return found.stream().collect(Collectors.toMap(SafetyInformation::getId, SafetyInformation::getInformationType));
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

        Long actorUserId = reason == RevokeReason.SHARING_EXCLUDED ? ownerUserId : null;
        List<InformationAccessLog> logs = new ArrayList<>();

        for (RequestInformationPermission permission : permissions) {
            if (permission.getStatus() == PermissionStatus.REVOKED) {
                continue;
            }
            boolean wasActive = permission.isActive(); // 바꾸기 전에 확인해야 이력 대상인지 알 수 있음
            permission.revoke(reason, revokedAt);

            if (wasActive) {
                logs.add(InformationAccessLog.create(permission.getId(), ownerUserId, actorUserId,
                        permission.getInformationId(), permission.getInformationType(),
                        AccessEventType.AUTO_REVOKED, revokedAt));
            }
        }

        accessLogRepository.saveAll(logs);
    }

    private BusinessException invalidSelection() {
        return new BusinessException(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
    }
}
