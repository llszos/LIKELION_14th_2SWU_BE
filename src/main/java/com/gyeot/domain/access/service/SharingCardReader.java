package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.access.service.SharingCardRules.ItemJudgement;
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
 * 판단 순서 (1~4의 규칙 자체는 SharingCardRules에 있고, 이 클래스는 잠그지 않는 조회로 값을 모아 그 규칙을 부릅니다)
 * 1. 요청 조건: 요청이 있고, 요청자가 정보 주인이고, 일반 요청이고, 수락됨이나 진행 중이고, 종료 시각 전이고,
 *    이 카드의 지정이 지금 담당자의 지정이어야 합니다. 하나라도 아니면 카드를 뺍니다. (자동으로 끝난 공유)
 * 2. 항목별 최신 권한: 같은 항목의 권한 중 가장 나중에 만든 행으로 판단합니다. 과거 철회 기록이 남아 있어도
 *    다시 허용이나 공개 항목 제외 뒤라면 최신 행 기준으로 봅니다.
 * 3. 원본 확인: 항목 원본이 남아 있고 주인이 정보 주인인 항목만 셉니다.
 * 4. 상태: 유효한 ACTIVE 항목이 있으면 SHARING(그 항목만 표시), 없고 유효한 직접 철회 항목이 있으면 REVOKED,
 *    둘 다 없으면 빈 카드라 뺍니다. 원본 확인 뒤에 정해, 원본이 사라진 권한 때문에 상태를 잘못 정하지 않습니다.
 * 5. canReallow: REVOKED 카드만 요청자 → 담당자 관계와 담당자의 상황 참여를 확인합니다. (다시 허용 가능성을 미리 안내하기 위함)
 *    실제 다시 허용 API는 잠금을 잡은 뒤 다시 검증합니다. 공유 중 목록(findCards)에서만 계산합니다.
 *
 * 쓰는 곳
 * - findCards: 공유 중 목록. SHARING과 REVOKED 카드를 모두 돌려주고 canReallow까지 계산합니다.
 * - findSharingCards: 공유 중 건수(SharingCountReader). SHARING 카드만 돌려주고 canReallow 계산(A, B 조회)을 하지 않습니다.
 * - findViewableCards: 받은 정보. findSharingCards 결과에 관계와 상황 참여 확인을 더합니다.
 * - 세 경로 모두 1~4 판단(요청 조건, 최신 권한, 원본 확인, 상태)은 같은 코드를 씁니다. 목록과 건수, 받은 정보가 어긋나지 않게 하기 위함입니다.
 *
 * - 급한 요청(F-13, SHOULD)은 이번 범위에서 카드로 만들지 않습니다. (전달받은 정보 조회 #45와 같은 범위)
 * - 공유 중 목록(findCards)의 SHARING 카드는 관계와 상황 참여를 확인하지 않습니다. 관계 해제와 상황 나가기는 C가 지정을 마감해
 *   권한이 회수되지만, 그 처리 전 잠시는 목록에 카드가 보여도 실제 조회(#45)는 막힐 수 있습니다. 카드마다 A, B 조회가 붙는 비용을 고려한 선택입니다.
 * - 받은 정보(findViewableCards)는 받는 사람이 "지금 볼 수 있는 것만" 보여 주므로, 전달받은 정보 조회(#45)와 같이
 *   정보 주인 → 받는 사람 관계와 받는 사람의 상황 참여까지 확인합니다. 받는 사람 한 명 기준이라 관계 확인은 한 번입니다.
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
    private final RelationshipQueryRepository relationshipQueryRepository;    // A: 요청자 → 담당자 관계 (canReallow, 받은 정보)
    private final SituationParticipantReader situationParticipantReader;      // B: 담당자의 상황 참여 (canReallow, 받은 정보)
    private final Clock clock;                                                // 지금 시각 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

    /**
     * 정보 주인의 공유 카드를 판단해 자동 회수 시각 빠른 순으로 돌려줍니다.
     *
     * @param ownerUserId    정보 주인 ID
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 거르지 않음 (C-06 전체, R-02-01은 한 사람)
     * @return 공유 카드 목록. 없으면 빈 목록
     */
    public List<SharingCardView> findCards(Long ownerUserId, Long receiverUserId) {
        return judgeCards(ownerUserId, receiverUserId, true); // 철회됨 카드의 '다시 허용' 버튼 표시 여부까지 계산
    }

    /**
     * 정보 주인의 공유 중(SHARING) 카드만 돌려줍니다. (공유 중 건수, 받은 정보)
     *
     * - 판단 1~4는 findCards와 같고, 철회됨 카드는 뺍니다.
     * - SHARING 카드의 canReallow는 원래 항상 false라, 철회됨 카드에만 붙는 관계(A)와 상황 참여(B) 조회를 하지 않습니다.
     *
     * @param ownerUserId    정보 주인 ID
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 거르지 않음 (건수는 전체, 받은 정보는 한 사람)
     * @return 공유 중 카드 목록 (자동 회수 시각 빠른 순). 없으면 빈 목록
     */
    public List<SharingCardView> findSharingCards(Long ownerUserId, Long receiverUserId) {
        return judgeCards(ownerUserId, receiverUserId, false).stream()
                .filter(card -> card.status() == SharingStatus.SHARING) // 철회됨 카드는 빼고 공유 중 카드만
                .toList();                                              // judgeCards의 순서를 그대로 유지
    }

    /**
     * 받는 사람이 지금 볼 수 있는 카드만 돌려줍니다. (받은 정보 조회, R-02-01)
     *
     * - findSharingCards(정보 주인, 받는 사람)의 카드 중, 정보 주인 → 받는 사람 관계가 유효하고
     *   받는 사람이 그 요청의 상황에 참여 중(ACTIVE)인 카드만 남깁니다. (전달받은 정보 조회 #45와 같은 기준)
     * - 철회됨 카드는 받는 사람에게는 볼 수 없는 정보라 뺍니다.
     * - 일반 요청은 상황 참여가 필수라, 상황 ID가 없으면 확인을 건너뛰지 않고 뺍니다.
     *
     * @param ownerUserId  정보 주인 ID (화면의 '이 사람')
     * @param viewerUserId 받는 사람 ID (로그인한 사용자)
     * @return 볼 수 있는 카드 목록 (자동 회수 시각 빠른 순). 없으면 빈 목록
     */
    public List<SharingCardView> findViewableCards(Long ownerUserId, Long viewerUserId) {
        // 관계는 정보 주인 → 받는 사람 방향 하나라 카드마다가 아니라 한 번만 확인 (나의 사람 해제는 해제한 방향만 회수)
        if (!relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                ownerUserId, viewerUserId, RelationshipStatus.ACTIVE)) {
            return List.of();
        }
        return findSharingCards(ownerUserId, viewerUserId).stream()                     // 지금 공유 중인 카드만 (A, B 조회 없음)
                .filter(card -> card.situationId() != null                              // 일반 요청은 상황 필수
                        && situationParticipantReader.isActiveParticipant(card.situationId(), viewerUserId)) // 나가기, 제외면 뺌
                .toList();                                                              // findSharingCards의 순서를 그대로 유지
    }

    // === 카드 판단 공통 (1~5) ===

    /**
     * 정보 주인의 공유 카드를 판단 순서 1~5대로 만들어 자동 회수 시각 빠른 순으로 돌려줍니다.
     *
     * @param ownerUserId    정보 주인 ID
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 거르지 않음
     * @param withReallow    철회됨 카드의 canReallow를 계산할지 (true는 공유 중 목록만. false면 항상 false로 두고 A, B를 조회하지 않음)
     * @return 공유 카드 목록 (SHARING, REVOKED). 없으면 빈 목록
     */
    private List<SharingCardView> judgeCards(Long ownerUserId, Long receiverUserId, boolean withReallow) {

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
            if (SharingCardRules.isOpenSharing(request, ownerUserId, assignmentId, receiverUserId, now)) {
                sources.put(assignmentId, new CardSource(request, SharingCardRules.latestByInformationId(entry.getValue())));
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
            toCard(entry.getKey(), entry.getValue(), items, ownerUserId, withReallow).ifPresent(cards::add);
        }
        cards.sort(CARD_ORDER); // 자동 회수 시각 빠른 순, 같으면 지정 ID 순
        return cards;
    }

    // === 3~5. 원본 확인, 상태, canReallow (3, 4는 SharingCardRules) ===

    /**
     * 요청 조건을 통과한 카드 하나를 최종 카드로 만듭니다. 보여 줄 항목이 없으면 빈 Optional입니다.
     *
     * @param assignmentId 공유 카드 ID (대상 지정 ID)
     * @param source       요청 정보와 항목별 최신 권한
     * @param items        정보 주인의 항목 원본 (항목 ID → 항목). 없는 ID는 삭제됐거나 다른 사람 항목
     * @param ownerUserId  정보 주인 ID (canReallow의 관계 확인용)
     * @param withReallow  canReallow를 계산할지 (false면 A, B를 조회하지 않고 false)
     */
    private Optional<SharingCardView> toCard(Long assignmentId, CardSource source,
                                             Map<Long, SafetyInformation> items, Long ownerUserId,
                                             boolean withReallow) {

        // 3, 4. 원본이 남은 항목만으로 상태와 보여 줄 항목을 정함 (목록과 쓰기가 같은 규칙, 빈 카드면 뺌)
        Optional<ItemJudgement> judged = SharingCardRules.judgeItems(source.latest(), items);
        if (judged.isEmpty()) {
            return Optional.empty();
        }
        SharingStatus status = judged.get().status();

        // 5. 철회됨 카드만 다시 허용 가능 여부를 확인
        SharingRequestInfo request = source.request();

        // 공유 중 카드는 다시 허용할 대상이 없어 항상 false. 철회됨 카드만 관계와 상황 참여를 조회 (A, B 조회를 필요한 카드로 한정)
        // 건수와 받은 정보(withReallow = false)는 철회됨 카드를 어차피 버리므로 조회하지 않음
        boolean canReallow = withReallow && status == SharingStatus.REVOKED && canReopen(request, ownerUserId);

        return Optional.of(new SharingCardView(
                assignmentId,
                request.requestId(),
                request.assigneeId(),   // 받는 사람 = 지금 담당자 (요청 조건에서 이 지정의 담당자임을 확인함)
                request.situationId(),  // 받은 정보 조회의 상황 참여 확인용 (응답에는 내려가지 않음)
                request.helpType(),
                request.startsAt(),
                request.endsAt(),       // 자동 회수 시각 = 요청 종료 시각 (일반 요청)
                status,
                canReallow,
                judged.get().names())); // 작성순
    }

    /**
     * 다시 열 수 있는 상태인지: 요청자 → 담당자 관계가 유효하고, 담당자가 상황에 참여 중인지 (요청 조건은 이미 통과)
     * 화면 버튼 표시용입니다. 실제 다시 허용은 잠금을 잡은 뒤 같은 조건을 다시 확인합니다.
     *
     * - 목록의 철회됨 카드(canReallow)와 직접 철회 응답(SharingRevokeTransaction)이 씁니다.
     *   다른 빈에서 프록시를 거쳐 부르므로 public입니다. (프록시는 private 메서드를 가로채지 못함)
     *
     * @param request     요청 조건을 통과한 요청 정보 (assigneeId가 있음)
     * @param ownerUserId 정보 주인 ID (요청자)
     */
    public boolean canReopen(SharingRequestInfo request, Long ownerUserId) {

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
