package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.access.service.SharingCardRules.ItemJudgement;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공유 카드 하나의 항목과 권한을 잠그고, 잠근 결과를 돌려줍니다. (공유 직접 철회, 다시 허용의 공통 단계, F-30, #46)
 *
 * 잠금 순서 (C 요청 → 항목 → 권한, #38. C 요청 잠금은 호출한 쪽이 먼저 잡음)
 * 1. 잠글 대상 ID만 조회: 카드의 권한(ACTIVE, REVOKED)이 가리키는 항목 ID. 권한 엔티티는 읽지 않습니다.
 * 2. 항목 잠금(ID 오름차순)과 원본 확보: 잠근 원본 중 정보 주인의 것만 원본으로 씁니다. (다시 잠금 조회하지 않음)
 * 3. 권한 잠금(권한 ID로 하나씩): 이 트랜잭션에서 처음 읽는 권한 엔티티라 최신 상태입니다.
 *
 * - 잠그고 나누어 돌려주는 데까지만 맡습니다. 404, 409 같은 오류 선택과 철회, 다시 허용 처리는 각 서비스가 합니다.
 * - 잠금 전에 권한 엔티티를 읽으면 뒤의 잠금 조회가 영속성 컨텍스트의 예전 객체를 돌려줘 최신 상태로 판단하지 못합니다.
 *   그래서 1에서는 항목 ID만 읽고, 권한 엔티티는 3의 잠금 조회로 처음 읽습니다. 호출한 쪽도 그 전에 권한 엔티티를 읽지 않아야 합니다.
 * - 항목이 삭제돼 information_id가 비어 있는 권한도 버리지 않고 따로 돌려줍니다. (다시 허용의 오류 구분용)
 * - 호출한 쪽 쓰기 트랜잭션 안에서만 실행합니다. (MANDATORY, 잠금은 그 트랜잭션이 끝날 때까지 유지)
 */
@Component                                            // 철회, 다시 허용 쓰기 트랜잭션이 주입받아 씀
@RequiredArgsConstructor                              // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(propagation = Propagation.MANDATORY)   // 호출한 쪽 트랜잭션에 참여 (없으면 예외)
public class SharingCardLocker {

    /** 카드 판단에 필요한 권한 상태 (공유 중인 ACTIVE, 직접 철회 기록을 포함한 REVOKED) */
    static final List<PermissionStatus> CARD_STATUSES = List.of(PermissionStatus.ACTIVE, PermissionStatus.REVOKED);

    private final RequestInformationPermissionRepository permissionRepository; // 잠글 항목 ID (엔티티 없이 ID만)
    private final PermissionWriter permissionWriter;                          // 항목, 권한 잠금

    /**
     * 카드의 항목과 권한을 잠그고, 잠근 결과를 나누어 돌려줍니다.
     *
     * @param ownerUserId  정보 주인 ID (원본 주인 확인용)
     * @param assignmentId 공유 카드 ID
     * @return 잠근 원본, 항목 ID가 있는 권한, 항목이 삭제된 권한 이력
     */
    public LockedCard lock(Long ownerUserId, Long assignmentId) {

        // 1. 잠글 항목 ID만 조회 (권한 엔티티를 읽지 않아, 3의 잠금 조회가 최신 상태를 돌려줌)
        List<Long> informationIds = permissionRepository.findInformationIdsByAssignmentIdAndStatusIn(assignmentId, CARD_STATUSES);

        // 2. 항목을 오름차순으로 잠그고, 잠근 원본 중 정보 주인의 것만 원본으로 씀 (잠금을 쥔 동안 삭제되지 않음)
        Map<Long, SafetyInformation> items = permissionWriter.lockItems(informationIds).stream()
                .filter(item -> Objects.equals(item.getOwnerId(), ownerUserId)) // 정보 주인 = 요청자 (방어)
                .collect(Collectors.toMap(SafetyInformation::getId, Function.identity()));

        // 3. 권한을 ID로 하나씩 잠그고, 항목 ID가 있는 권한과 항목이 삭제된 권한(information_id NULL)으로 나눔
        Map<Boolean, List<RequestInformationPermission>> byDeleted =
                permissionWriter.lockPermissionsById(assignmentId, CARD_STATUSES).stream()
                        .collect(Collectors.partitioningBy(permission -> permission.getInformationId() == null));

        return new LockedCard(items, byDeleted.get(false), byDeleted.get(true));
    }

    /**
     * 잠근 카드 하나의 결과입니다.
     *
     * @param items          잠근 항목 원본 중 정보 주인의 것 (항목 ID → 항목). 없는 ID는 삭제됐거나 다른 사람 항목
     * @param permissions    information_id가 있는 권한 (권한 ID 오름차순, 카드 판단용)
     * @param deletedHistory 항목이 삭제돼 information_id가 비어 있는 권한 (권한 ID 오름차순, 오류 구분용.
     *                       어느 항목의 기록인지 알 수 없어 항목별 최신 판단에는 쓰지 않음)
     */
    public record LockedCard(Map<Long, SafetyInformation> items,
                             List<RequestInformationPermission> permissions,
                             List<RequestInformationPermission> deletedHistory) {

        public LockedCard {
            items = Map.copyOf(items);                   // 만든 뒤 값이 바뀌지 않게 복사
            permissions = List.copyOf(permissions);
            deletedHistory = List.copyOf(deletedHistory);
        }

        /** 항목 ID가 있는 권한과 잠근 원본으로 카드 상태를 판단합니다. 원본이 없는 항목은 빠집니다. (공유 중 목록과 같은 규칙, 빈 카드면 빈 Optional) */
        public Optional<ItemJudgement> judge() {
            return SharingCardRules.judgeItems(SharingCardRules.latestByInformationId(permissions), items);
        }
    }
}
