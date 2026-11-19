package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.SafetyInformation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 공유 카드 판단의 공통 규칙입니다. (F-30, #46)
 * 조회(SharingCardReader: 목록, 건수, 받은 정보)와 쓰기(직접 철회, 다시 허용)가 같은 기준으로 카드를 판단하도록 모았습니다.
 *
 * - DB를 조회하지 않습니다. 넘겨받은 값으로만 판단합니다.
 *   데이터를 어떻게 읽을지(일반 조회인지 잠금 조회인지)는 경로마다 달라서 호출하는 쪽이 정합니다.
 *   조회 경로는 잠그지 않는 스냅샷으로, 쓰기 경로는 잠금으로 확보한 최신 행으로 이 규칙을 부릅니다.
 * - 관계(A)와 상황 참여(B) 확인은 DB 조회가 필요해 여기 두지 않고 각 서비스에 둡니다.
 *
 * 판단 순서 (SharingCardReader 클래스 설명의 1~4와 같음)
 * 1. isOpenSharing: 요청 조건
 * 2. latestByInformationId: 항목별 최신 권한
 * 3, 4. judgeItems: 원본 확인 뒤 상태 결정
 *
 * 공개 항목 선택지(SharingOptionService, #138)도 2번 항목별 최신 권한을 같은 기준으로 쓰도록,
 * 클래스와 latestByInformationId만 공개합니다. 나머지 카드 판단은 access 패키지 안에서만 씁니다.
 */
public final class SharingCardRules {

    private SharingCardRules() {
        // 정적 메서드만 있어 객체를 만들지 않음
    }

    // === 1. 요청 조건 ===

    /**
     * 이 카드가 지금 유효한 공유인지 판단합니다. (요청 상태, 기한, 지금 담당자, 받는 사람 필터)
     *
     * @param request        C 요청 정보 (null이면 요청이 없음)
     * @param ownerUserId    정보 주인 ID
     * @param assignmentId   이 카드의 지정 ID
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 거르지 않음
     * @param now            비교 기준 시각 (한 번의 판단에서 모든 카드에 같은 값)
     */
    static boolean isOpenSharing(SharingRequestInfo request, Long ownerUserId, Long assignmentId,
                                 Long receiverUserId, LocalDateTime now) {

        if (request == null) {
            return false; // C에 요청이 없음 (방어)
        }

        if (!Objects.equals(request.requesterId(), ownerUserId)) {
            return false; // 정보 주인 = 요청자 (방어, 다른 사람의 공유)
        }

        if (request.requestType() != RequestType.GENERAL) {
            return false; // 급한 요청은 SHOULD 범위라 이번에는 카드로 만들지 않음 (#45와 같은 범위)
        }

        if (request.status() != CareRequestStatus.ACCEPTED && request.status() != CareRequestStatus.IN_PROGRESS) {
            return false; // 수락 전이거나 끝난 요청 (끝난 공유는 목록에서 빠짐)
        }

        if (request.endsAt() == null || !now.isBefore(request.endsAt())) {
            return false; // 종료 시각이 지남 (종료 시각 미포함, 스케줄러가 완료로 바꾸기 전 1분 사이 포함)
        }

        if (request.assignmentId() == null || request.assigneeId() == null
                || !Objects.equals(request.assignmentId(), assignmentId)) {
            return false; // 이 카드의 지정이 지금 담당자의 지정이 아님 (수락 철회, 대상 변경)
        }

        return receiverUserId == null || Objects.equals(request.assigneeId(), receiverUserId); // R-02-01 한 사람 기준
    }

    // === 2. 항목별 최신 권한 ===

    /**
     * 항목 ID → 그 항목의 가장 나중에 만든 권한을 돌려줍니다.
     * 과거 철회 기록이 남아 있어도 다시 허용이나 공개 항목 제외 뒤라면 최신 행으로 판단하기 위함입니다.
     *
     * @param permissions 한 지정의 권한 (information_id가 있는 행만. 순서는 상관없음)
     * @return 항목 ID → 최신 권한 (입력에서 처음 나온 항목 순서)
     */
    public static Map<Long, RequestInformationPermission> latestByInformationId(List<RequestInformationPermission> permissions) {

        Map<Long, RequestInformationPermission> latest = new LinkedHashMap<>();

        for (RequestInformationPermission permission : permissions) {
            latest.merge(permission.getInformationId(), permission,
                    (previous, current) -> current.getId() > previous.getId() ? current : previous); // 입력 순서에 기대지 않고 ID가 큰 행을 최신으로 봄
        }
        return latest;
    }

    // === 3, 4. 원본 확인과 상태 ===

    /**
     * 원본이 남아 있는 항목만으로 카드 상태와 보여 줄 항목을 정합니다. 보여 줄 항목이 없으면 빈 Optional(빈 카드)입니다.
     *
     * - 원본 확인 뒤에 상태를 정해, 원본이 사라진 권한 때문에 상태를 잘못 정하지 않습니다.
     * - 유효한 ACTIVE 항목이 있으면 SHARING (그 항목만 표시), 없고 유효한 직접 철회 항목이 있으면 REVOKED,
     *   둘 다 없으면 빈 카드입니다. 그 밖의 최신 권한(수락 전, 공개 항목 제외 등)은 카드에 보이지 않습니다.
     *
     * @param latest 항목 ID → 최신 권한 (latestByInformationId 결과)
     * @param items  정보 주인의 항목 원본 (항목 ID → 항목). 없는 ID는 삭제됐거나 다른 사람 항목으로 봄
     */
    static Optional<ItemJudgement> judgeItems(Map<Long, RequestInformationPermission> latest,
                                              Map<Long, SafetyInformation> items) {

        List<Shown> active = new ArrayList<>();
        List<Shown> revoked = new ArrayList<>();

        for (Map.Entry<Long, RequestInformationPermission> entry : latest.entrySet()) {

            SafetyInformation item = items.get(entry.getKey());
            if (item == null) {
                continue; // 원본이 없거나 다른 사람 항목 (스냅샷이나 잠금 조회에 없는 항목 포함)
            }

            RequestInformationPermission permission = entry.getValue();
            if (permission.isActive()) {
                active.add(new Shown(permission, item));
            } else if (permission.getRevokeReason() == RevokeReason.OWNER_REVOKED) {
                revoked.add(new Shown(permission, item));
            } // 그 밖(수락 전, 공개 항목 제외 등)은 카드에 보여 주지 않음
        }

        if (!active.isEmpty()) {
            return Optional.of(ItemJudgement.of(SharingStatus.SHARING, active));  // 섞인 카드도 ACTIVE 항목만 표시
        }
        if (!revoked.isEmpty()) {
            return Optional.of(ItemJudgement.of(SharingStatus.REVOKED, revoked)); // 직접 철회한 항목만 남음
        }
        return Optional.empty(); // 남은 항목이 없는 빈 카드 (11/14 FE 답변: 목록에서 뺌)
    }

    /** 보여 줄 항목 하나와 그 항목의 최신 권한 */
    private record Shown(RequestInformationPermission permission, SafetyInformation item) {
    }

    /**
     * 원본 확인 뒤의 카드 상태와 보여 줄 항목입니다.
     *
     * @param status      카드 상태 (SHARING 또는 REVOKED)
     * @param permissions 보여 줄 항목의 최신 권한 (SHARING이면 ACTIVE, REVOKED면 직접 철회. 직접 철회 시 회수 대상)
     * @param names       카드에 보여 줄 항목 이름 (작성순 = 항목 ID 오름차순)
     */
    record ItemJudgement(SharingStatus status, List<RequestInformationPermission> permissions,
                         List<SharedItemName> names) {

        ItemJudgement {
            permissions = List.copyOf(permissions); // 만든 뒤 목록이 바뀌지 않게 복사
            names = List.copyOf(names);
        }

        /** 보여 줄 항목을 작성순으로 정렬해, 권한과 이름 목록의 순서를 같게 만듦 */
        private static ItemJudgement of(SharingStatus status, List<Shown> shown) {
            List<Shown> sorted = shown.stream()
                    .sorted(Comparator.comparing(s -> s.item().getId())) // 작성순
                    .toList();
            return new ItemJudgement(status,
                    sorted.stream().map(Shown::permission).toList(),
                    sorted.stream().map(s -> SharedItemName.of(s.item().getInformationType(), s.item().getTitle())).toList());
        }
    }
}
