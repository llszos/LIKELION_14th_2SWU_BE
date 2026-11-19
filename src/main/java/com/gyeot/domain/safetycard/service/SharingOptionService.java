package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.access.service.SharingCardRules;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.CategoryOption;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.ItemOption;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.policy.DefaultSharingTypes;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 요청 미리보기에서 공개할 항목을 고를 수 있도록 분류별 내 항목과 기본 선택값을 만듭니다. (F-03)
 * - requestId가 없으면(새 요청) 도움 종류의 기본 공개 유형에 속한 항목을 선택합니다.
 * - requestId가 있으면(요청 수정 H-03-01-02, 다시 부탁하기 N-06) 그 요청의 마지막 대상에게 공개했던 항목을 선택합니다.
 *   N-06은 이전 대상이 거절·마감한 뒤라 권한이 이미 끝났으므로, 공개 중(PENDING·ACTIVE)만으로는 찾을 수 없습니다.
 * - 마지막 대상 지정은 C의 부탁 순서(assignment_order) 기준입니다. (#138)
 *   권한 테이블의 지정 ID로 찾으면, 마지막 지정에 공개 항목이 0개일 때(권한 행 없음) 이전 지정의 항목을 잘못 선택합니다.
 * - updated(공유 이후 수정 여부)도 같은 마지막 지정의 항목별 최신 권한으로 판단합니다. (#138, 피그마 코멘트 #91)
 *   selected와 따로 계산하므로, 직접 철회하거나 공개에서 뺀 항목(selected=false)도 공유 뒤 수정했으면 true입니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharingOptionService {

    private final SafetyInformationRepository safetyInformationRepository;     // 내 안심카드 항목 조회
    private final RequestInformationPermissionRepository permissionRepository; // 마지막 지정에 공개했던 권한 조회
    private final CareRequestSharingReader careRequestSharingReader;           // C: 요청의 마지막 대상 지정 (#138)

    // 정보 주인이 직접 뺀 항목은 다시 선택해 두지 않음 (삭제된 항목은 information_id가 NULL이라 권한을 모을 때 빠짐)
    private static final List<RevokeReason> EXCLUDED_REASONS =
            List.of(RevokeReason.SHARING_EXCLUDED, RevokeReason.OWNER_REVOKED, RevokeReason.ITEM_DELETED);

    public SharingOptionsResponse getSharingOptions(Long userId, HelpType helpType, Long requestId) {
        List<SafetyInformation> items = safetyInformationRepository.findAllByOwnerIdOrderByIdAsc(userId);
        // requestId가 있으면 그 요청의 마지막 대상 지정에서 항목 ID → 최신 권한 (새 요청이면 빈 Map)
        Map<Long, RequestInformationPermission> latest = requestId == null ? Map.of() : latestPermissions(requestId);
        Predicate<SafetyInformation> isSelected = selectionRule(helpType, requestId, latest);

        // 분류 4개를 enum 순서대로 모두 내려줌 (항목이 없으면 빈 배열)
        List<CategoryOption> categories = Arrays.stream(InformationCategory.values())
                .map(category -> new CategoryOption(
                        category,
                        category.getDescription(),
                        items.stream()
                                .filter(item -> item.getInformationType().getCategory() == category)
                                .map(item -> toItemOption(item, isSelected.test(item),
                                        isUpdatedSinceShared(item, latest.get(item.getId()))))
                                .toList()))
                .toList();

        return new SharingOptionsResponse(categories);
    }

    /**
     * 요청의 마지막 대상 지정에서 항목 ID → 최신 권한을 돌려줍니다. (#138)
     *
     * - 마지막 지정은 C가 알려 줍니다. (assignment_order가 가장 큰 지정, 마감 여부 무관)
     * - 지정이 없거나 그 지정의 권한이 없으면(공개 항목 0개) 빈 Map입니다. 이전 지정으로 돌아가지 않습니다.
     * - 항목별 최신 행은 공유 관리와 같은 기준(SharingCardRules)입니다. 철회 후 다시 허용했다면 새 권한이 최신입니다.
     * - 삭제된 항목의 권한은 information_id가 비워져 있어 빼고 모읍니다.
     */
    private Map<Long, RequestInformationPermission> latestPermissions(Long requestId) {
        return careRequestSharingReader.findLastAssignmentId(requestId)
                .map(assignmentId -> SharingCardRules.latestByInformationId(
                        permissionRepository.findAllByAssignmentIdOrderByIdAsc(assignmentId).stream()
                                .filter(permission -> permission.getInformationId() != null)
                                .toList()))
                .orElse(Map.of());
    }

    private Predicate<SafetyInformation> selectionRule(HelpType helpType, Long requestId,
                                                       Map<Long, RequestInformationPermission> latest) {
        if (requestId == null) {
            Set<InformationType> defaultTypes = DefaultSharingTypes.of(helpType);
            return item -> defaultTypes.contains(item.getInformationType());
        }

        // 최신 권한이 열려 있거나(사유 없음) 정보 주인이 뺀 것(제외 사유)이 아니면 마지막 대상에게 공개했던 항목
        // 공개했던 항목 ID와 내 항목 ID를 비교하므로, 남의 요청 ID를 넣어도 내 항목이 선택될 일이 없음
        Set<Long> sharingIds = latest.entrySet().stream()
                .filter(entry -> !isExcludedByOwner(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return item -> sharingIds.contains(item.getId());
    }

    /**
     * 공유 이후 수정 여부 (#138)
     * 마지막 대상 지정에서 그 항목의 최신 권한이 실제로 공유된 시각(granted_at)보다 항목을 나중에 수정했으면 true입니다.
     *
     * - 새 요청(requestId 없음), 마지막 지정이 없거나 그 지정에 이 항목의 권한이 없음: 권한이 null이라 false
     * - 수락 전이라 공유되지 않은 권한(PENDING, 수락 전에 끝난 REVOKED): granted_at이 없어 false
     * - 같은 시각이면 공유와 동시라 수정으로 보지 않습니다. (전달받은 정보 조회의 '갱신' 판단과 같은 기준)
     * - 직접 철회, 공개 제외로 끝난 권한도 granted_at이 남아 있어, 공유 뒤 수정했으면 true입니다. (selected와 별개)
     * - 철회 후 다시 허용했다면 최신 권한(새로 만든 행)의 granted_at 기준입니다.
     */
    private static boolean isUpdatedSinceShared(SafetyInformation item, RequestInformationPermission latestPermission) {
        return latestPermission != null
                && latestPermission.getGrantedAt() != null
                && item.getUpdatedAt() != null
                && item.getUpdatedAt().isAfter(latestPermission.getGrantedAt());
    }

    // List.of는 null을 넣어 확인하면 예외가 나므로, 사유가 없는(열린) 권한을 먼저 거름
    private static boolean isExcludedByOwner(RequestInformationPermission permission) {
        RevokeReason reason = permission.getRevokeReason();
        return reason != null && EXCLUDED_REASONS.contains(reason);
    }

    private ItemOption toItemOption(SafetyInformation item, boolean selected, boolean updated) {
        InformationType type = item.getInformationType();
        return new ItemOption(
                item.getId(),
                type,
                type.getDescription(),
                item.getTitle(),
                item.getInformationValue(),
                type.isSensitive(),
                selected,
                updated,
                item.getUpdatedAt());
    }
}
