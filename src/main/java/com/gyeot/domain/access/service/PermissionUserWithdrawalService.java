package com.gyeot.domain.access.service;

import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 회원 탈퇴 시 정보 주인의 안심카드와 연결된 D 권한 행을 정리합니다. (#49) */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionUserWithdrawalService {

    private final SafetyInformationRepository safetyInformationRepository;
    private final PermissionWriter permissionWriter;
    private final InformationAccessLogRepository accessLogRepository;
    private final RequestInformationPermissionRepository permissionRepository;

    /**
     * A의 탈퇴 트랜잭션에 참여합니다. 카드 → 권한 순서로 잠그고,
     * 회수 이력을 남긴 뒤 접근 이력의 permission_id를 끊고 권한·원본을 삭제합니다.
     */
    public void cleanupOwnerData(UserWithdrawnEvent event) {
        List<SafetyInformation> lockedItems = lockOwnedItems(event.userId());
        if (lockedItems.isEmpty()) {
            return; // 이벤트 재전달에도 이미 정리된 데이터를 다시 변경하지 않음
        }

        List<Long> informationIds = lockedItems.stream().map(SafetyInformation::getId).toList();
        List<Long> permissionIds = permissionWriter.revokeForOwnerWithdrawal(
                informationIds, event.userId(), event.occurredAt());

        // 접근 이력은 2년 보존합니다. 권한만 삭제하고 FK처럼 연결된 ID는 명시적으로 NULL로 바꿉니다.
        if (!permissionIds.isEmpty()) {
            accessLogRepository.flush(); // 방금 추가한 AUTO_REVOKED 이력도 먼저 저장한 뒤 permission_id를 비움
            accessLogRepository.clearPermissionIds(permissionIds);
            permissionRepository.deleteAllByPermissionIdIn(permissionIds);
        }
        safetyInformationRepository.deleteAll(lockedItems);
    }

    private List<SafetyInformation> lockOwnedItems(Long ownerId) {
        List<SafetyInformation> locked = new ArrayList<>();
        for (Long id : safetyInformationRepository.findIdsByOwnerIdOrderByIdAsc(ownerId)) {
            safetyInformationRepository.findByIdForUpdate(id)
                    .filter(item -> ownerId.equals(item.getOwnerId()))
                    .ifPresent(locked::add);
        }
        return locked;
    }
}

