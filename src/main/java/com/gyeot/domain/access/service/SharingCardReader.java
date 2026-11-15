package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공유 관리 카드(요청, 담당자 묶음)를 판단합니다. (노션 D 파트 API 명세 '공유 중 목록 조회', F-30, #46)
 * 공유 중 목록, 받은 정보, 공유 중 건수가 같은 기준을 쓰도록 판단을 이곳에 모았습니다.
 *
 * 판단 순서
 * 1. 요청 조건: 요청이 있고, 요청자가 정보 주인이고, 일반 요청이고, 수락됨이나 진행 중이고, 종료 시각 전이고,
 *    이 카드의 지정이 지금 담당자의 지정이어야 합니다. 하나라도 아니면 카드를 뺍니다. (자동으로 끝난 공유)
 * 2. 항목별 최신 권한: 같은 항목의 권한 중 가장 나중에 만든 행으로 판단합니다. 과거 철회 기록이 남아 있어도
 *    다시 허용이나 공개 항목 제외 뒤라면 최신 행 기준으로 봅니다.
 * 3. 원본 확인: 항목 원본이 남아 있고 주인이 정보 주인인 항목만 셉니다.
 * 4. 상태: 유효한 ACTIVE 항목이 있으면 SHARING(그 항목만 표시), 없고 유효한 직접 철회 항목이 있으면 REVOKED,
 *    둘 다 없으면 빈 카드라 뺍니다. 원본 확인 뒤에 정해, 원본이 사라진 권한 때문에 상태를 잘못 정하지 않습니다.
 * 5. canReallow: REVOKED 카드만 요청자 → 담당자 관계와 담당자의 상황 참여를 확인합니다. (다시 허용 가능성을 미리 안내하기 위함)
 *    실제 다시 허용 API는 잠금을 잡은 뒤 다시 검증합니다.
 *
 * - 급한 요청(F-13, SHOULD)은 이번 범위에서 카드로 만들지 않습니다. (전달받은 정보 조회 #45와 같은 범위)
 * - SHARING 카드는 관계와 상황 참여를 확인하지 않습니다. 관계 해제와 상황 나가기는 C가 지정을 마감해 권한이 회수되지만,
 *   그 처리 전 잠시는 목록에 카드가 보여도 실제 조회(#45)는 막힐 수 있습니다. 카드마다 A, B 조회가 붙는 비용을 고려한 선택입니다.
 * - 잠그지 않는 조회입니다. 같은 트랜잭션의 첫 일반 DB 조회 시점의 스냅샷 기준이며, 그 뒤 커밋된 변경은 다음 조회부터 반영됩니다.
 */
@Component                        // 공유 관리 서비스들이 주입받아 쓰는 판단 도우미
@RequiredArgsConstructor          // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(readOnly = true)   // 조회만 하므로 읽기 전용 (호출한 쪽 트랜잭션이 있으면 그 트랜잭션에 합류)
public class SharingCardReader {

    private static final Comparator<SharingCardView> CARD_ORDER =
            Comparator.comparing(SharingCardView::accessUntil)        // 자동 회수 시각 빠른 순 (명세)
                    .thenComparing(SharingCardView::assignmentId);   // 같으면 지정 ID 순 (순서 고정)

    private final RequestInformationPermissionRepository permissionRepository; // 카드 후보와 권한 이력
    private final CareRequestSharingReader careRequestSharingReader;          // C: 요청 상태, 지금 담당자
    private final SafetyInformationRepository safetyInformationRepository;    // 항목 원본 (이름 표시)
    private final RelationshipQueryRepository relationshipQueryRepository;    // A: 요청자 → 담당자 관계 (canReallow)
    private final SituationParticipantReader situationParticipantReader;      // B: 담당자의 상황 참여 (canReallow)
    private final Clock clock;                                                // 지금 시각 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

    /**
     * 정보 주인의 공유 카드를 판단해 자동 회수 시각 빠른 순으로 돌려줍니다.
     *
     * @param ownerUserId    정보 주인 ID
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 거르지 않음 (C-06 전체, R-02-01은 한 사람)
     * @return 공유 카드 목록. 없으면 빈 목록
     */
    public List<SharingCardView> findCards(Long ownerUserId, Long receiverUserId) {

        List<Long> candidateIds = permissionRepository.findSharingCandidateAssignmentIds(ownerUserId);

        if (candidateIds.isEmpty()) {
            return List.of(); // 공유 중이거나 직접 철회한 카드가 없음
        }

        // 지정별 권한 이력 (ID 오름차순이라 같은 항목은 나중에 만든 권한이 뒤에 옴)
        Map<Long, List<RequestInformationPermission>> permissionsByAssignment =
                permissionRepository.findItemPermissionsByAssignmentIds(candidateIds).stream()
                        .collect(Collectors.groupingBy(RequestInformationPermission::getAssignmentId,
                                LinkedHashMap::new, Collectors.toList()));

        if (permissionsByAssignment.isEmpty()) {
            return List.of(); // 후보의 항목이 모두 삭제됨
        }

        // C 요청 정보를 한 번에 조회 (카드마다 조회하지 않음)
        Set<Long> requestIds = permissionsByAssignment.values().stream()
                .map(permissions -> permissions.get(0).getRequestId()) // 한 지정의 권한은 모두 같은 요청
                .collect(Collectors.toSet());

        Map<Long, SharingRequestInfo> requests = careRequestSharingReader.findAllByIds(requestIds);
        LocalDateTime now = LocalDateTime.now(clock); // 모든 카드의 종료 시각을 같은 기준 시각으로 비교

        // 1. 요청 조건을 통과한 카드만 남기고, 2. 항목별 최신 권한을 구함
        Map<Long, CardSource> sources = new LinkedHashMap<>();
        for (Map.Entry<Long, List<RequestInformationPermission>> entry : permissionsByAssignment.entrySet()) {
            Long assignmentId = entry.getKey();
            SharingRequestInfo request = requests.get(entry.getValue().get(0).getRequestId());
            if (isOpenSharing(request, ownerUserId, assignmentId, receiverUserId, now)) {
                sources.put(assignmentId, new CardSource(request, latestByInformationId(entry.getValue())));
            }
        }

        if (sources.isEmpty()) {
            return List.of(); // 남은 카드가 없으면 항목 원본을 조회하지 않음
        }

        // 3. 남은 카드의 항목 원본을 한 번에 조회 (정보 주인의 것만)
        Set<Long> informationIds = new HashSet<>();
        sources.values().forEach(source -> informationIds.addAll(source.latest().keySet()));
        Map<Long, SafetyInformation> items = safetyInformationRepository.findAllById(informationIds).stream()
                .filter(item -> Objects.equals(item.getOwnerId(), ownerUserId)) // 정보 주인 = 요청자 (방어)
                .collect(Collectors.toMap(SafetyInformation::getId, Function.identity()));

        // 4. 유효 항목 기준 상태와 빈 카드, 5. canReallow
        List<SharingCardView> cards = new ArrayList<>();
        for (Map.Entry<Long, CardSource> entry : sources.entrySet()) {
            toCard(entry.getKey(), entry.getValue(), items, ownerUserId).ifPresent(cards::add);
        }
        cards.sort(CARD_ORDER); // 자동 회수 시각 빠른 순, 같으면 지정 ID 순
        return cards;
    }

    // === 1. 요청 조건 ===

    /** 이 카드가 지금 유효한 공유인지 (요청 상태, 기한, 지금 담당자, 받는 사람 필터) */
    private static boolean isOpenSharing(SharingRequestInfo request, Long ownerUserId, Long assignmentId,
                                         Long receiverUserId, LocalDateTime now) {

        if (request == null) {
            return false; // C에 요청이 없음 (방어)
        }

        if (!Objects.equals(request.requesterId(), ownerUserId)) {
            return false; // 정보 주인 = 요청자 (방어)
        }

        if (request.requestType() != RequestType.GENERAL) {
            return false; // 급한 요청은 SHOULD 범위라 이번에는 카드로 만들지 않음 (#45와 같은 범위)
        }

        if (request.status() != CareRequestStatus.ACCEPTED && request.status() != CareRequestStatus.IN_PROGRESS) {
            return false; // 수락 전이거나 끝난 요청 (끝난 공유는 목록에서 빠짐)
        }

        if (request.endsAt() == null || !now.isBefore(request.endsAt())) {
            return false; // 종료 시각이 지남 (스케줄러가 완료로 바꾸기 전 1분 사이 포함)
        }

        if (request.assignmentId() == null || request.assigneeId() == null
                || !Objects.equals(request.assignmentId(), assignmentId)) {
            return false; // 이 카드의 지정이 지금 담당자의 지정이 아님 (수락 철회, 대상 변경)
        }

        return receiverUserId == null || Objects.equals(request.assigneeId(), receiverUserId); // R-02-01 한 사람 기준
    }

    // === 2. 항목별 최신 권한 ===

    /** 항목 ID → 그 항목의 가장 나중에 만든 권한 (입력이 ID 오름차순이라 뒤의 행이 최신) */
    private static Map<Long, RequestInformationPermission> latestByInformationId(List<RequestInformationPermission> permissions) {

        Map<Long, RequestInformationPermission> latest = new LinkedHashMap<>();

        for (RequestInformationPermission permission : permissions) {
            latest.merge(permission.getInformationId(), permission,
                    (previous, current) -> current.getId() > previous.getId() ? current : previous); // 순서에 기대지 않고 ID로 한 번 더 확인
        }
        return latest;
    }

    // === 3~5. 원본 확인, 상태, canReallow ===

    /**
     * 요청 조건을 통과한 카드 하나를 최종 카드로 만듭니다. 보여 줄 항목이 없으면 빈 Optional입니다.
     *
     * @param assignmentId 공유 카드 ID (대상 지정 ID)
     * @param source       요청 정보와 항목별 최신 권한
     * @param items        정보 주인의 항목 원본 (항목 ID → 항목). 없는 ID는 삭제됐거나 다른 사람 항목
     * @param ownerUserId  정보 주인 ID (canReallow의 관계 확인용)
     */
    private Optional<SharingCardView> toCard(Long assignmentId, CardSource source,
                                             Map<Long, SafetyInformation> items, Long ownerUserId) {

        // 3. 원본이 남아 있는 항목만, 최신 권한이 ACTIVE인 것과 직접 철회인 것으로 나눔
        List<SafetyInformation> activeItems = new ArrayList<>();
        List<SafetyInformation> revokedItems = new ArrayList<>();

        for (Map.Entry<Long, RequestInformationPermission> entry : source.latest().entrySet()) {

            SafetyInformation item = items.get(entry.getKey());
            if (item == null) {
                continue; // 원본이 없거나 다른 사람 항목 (스냅샷에 없는 항목 포함)
            }

            RequestInformationPermission latest = entry.getValue();
            if (latest.isActive()) {
                activeItems.add(item);
            } else if (latest.getRevokeReason() == RevokeReason.OWNER_REVOKED) {
                revokedItems.add(item);
            } // 그 밖(수락 전, 공개 항목 제외 등)은 카드에 보여 주지 않음
        }

        // 4. 유효 항목 기준으로 상태를 정함 (원본 확인 뒤라 사라진 항목 때문에 상태가 틀리지 않음)
        SharingStatus status;
        List<SafetyInformation> shown;

        if (!activeItems.isEmpty()) {
            status = SharingStatus.SHARING;   // 볼 수 있는 항목이 있으면 공유 중 (섞인 카드도 ACTIVE 항목만 표시)
            shown = activeItems;
        } else if (!revokedItems.isEmpty()) {
            status = SharingStatus.REVOKED;   // 직접 철회한 항목만 남음
            shown = revokedItems;
        } else {
            return Optional.empty(); // 남은 항목이 없는 빈 카드는 목록에서 뺌 (11/14 FE 답변)
        }

        // 5. 철회됨 카드만 다시 허용 가능 여부를 확인
        SharingRequestInfo request = source.request();

        // 공유 중 카드는 다시 허용할 대상이 없어 항상 false. 철회됨 카드만 관계와 상황 참여를 조회 (A, B 조회를 필요한 카드로 한정)
        boolean canReallow = status == SharingStatus.REVOKED && canReopen(request, ownerUserId);

        List<SharedItemName> names = shown.stream()
                .sorted(Comparator.comparing(SafetyInformation::getId))                         // 작성순
                .map(item -> SharedItemName.of(item.getInformationType(), item.getTitle()))
                .toList();

        return Optional.of(new SharingCardView(
                assignmentId,
                request.requestId(),
                request.assigneeId(),   // 받는 사람 = 지금 담당자 (요청 조건에서 이 지정의 담당자임을 확인함)
                request.helpType(),
                request.startsAt(),
                request.endsAt(),       // 자동 회수 시각 = 요청 종료 시각 (일반 요청)
                status,
                canReallow,
                names));
    }

    /**
     * 다시 열 수 있는 상태인지: 요청자 → 담당자 관계가 유효하고, 담당자가 상황에 참여 중인지 (요청 조건은 이미 통과)
     * 화면 버튼 표시용입니다. 실제 다시 허용은 잠금을 잡은 뒤 같은 조건을 다시 확인합니다.
     */
    private boolean canReopen(SharingRequestInfo request, Long ownerUserId) {

        if (!relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                ownerUserId, request.assigneeId(), RelationshipStatus.ACTIVE)) {
            return false; // 관계가 끝남
        }
        return request.situationId() != null
                && situationParticipantReader.isActiveParticipant(request.situationId(), request.assigneeId()); // 일반 요청은 상황 참여 필수
    }

    /** 요청 조건을 통과한 카드의 요청 정보와 항목별 최신 권한 */
    private record CardSource(SharingRequestInfo request, Map<Long, RequestInformationPermission> latest) {
    }
}
