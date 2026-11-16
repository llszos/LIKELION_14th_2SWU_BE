package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 공유(권한)용 요청 조회 인터페이스입니다. (C → D)
 * D의 정보 조회 검증과 공유 관리 카드 구성에 필요한 요청 정보를 제공합니다.
 * - 화면 표시용 요약은 CareRequestSummaryReader(B)를 사용합니다. 사용 목적이 달라 따로 두었습니다.
 * - 요청 상태는 화면용 RequestDisplayStatus가 아니라 DB의 CareRequestStatus를 그대로 제공합니다.
 *   D는 이 값으로 접근 가능 여부를 판단합니다.
 * - 관계 유지 및 상황 참여 여부(ERD 8.3)는 포함하지 않았습니다.
 */
public interface CareRequestSharingReader {

    /**
     * 요청 하나를 조회합니다. (정보 조회 시 검증)
     * 없으면 빈 Optional를 반환합니다.
     * */
    Optional<SharingRequestInfo> findById(Long requestId);

    /**
     * 여러 요청을 한 번에 조회합니다. (공유 관리 카드 목록)
     * 요청마다 따로 조회하면 카드 수만큼 쿼리가 나가서 묶어서 조회합니다.
     * 없는 ID는 결과에서 빠집니다.
     */
    Map<Long, SharingRequestInfo> findAllByIds(Collection<Long> requestIds);

    /**
     * 사용자가 이 요청의 대상으로 지정된 적이 있는지 확인합니다. (D 전달받은 정보 조회 이슈 #45)
     * 접근 종료 안내(200 + accessible=false)를 보여 줄 사람과 무관한 사람(404)을 구분하는 데 사용합니다.
     * - 지금 지정이든 마감된 과거 지정이든, 수락했든 안 했든 지정 행이 하나라도 있으면 true
     * - 요청자 본인, 지정된 적 없는 사람, 없는 요청은 false
     *
     * findById의 assigneeId는 현재 담당자만 담고 취소, 만료되면 null이라 과거 대상을 알 수 없습니다.
     * 또 공개 항목이 0개인 요청은 권한 행도 없어 D 데이터만으로는 확인할 수 없어서 따로 두었습니다.
     */
    boolean hasBeenAssigned(Long requestId, Long userId);

    /**
     * 사용자가 이 요청의 지금 대상인지 확인합니다. (D 전달받은 정보 조회)
     * 응답을 기다리는 지금 대상(NOT_ACCEPTED)과, 요청이 다른 사람에게 넘어갔거나 수락을 철회한 예전 대상(ASSIGNMENT_CLOSED)을 구분하는 데 사용합니다.
     * - 마감되지 않은 지정의 대상이면 true (수락 전, 수락 후 모두). 요청당 열린 지정은 최대 1건입니다.
     * - 마감된 지정만 있거나, 지정된 적 없거나, 요청이 없으면 false
     *
     * 함께 알아 둘 C 규칙입니다!
     * - "어려움"으로 답해도 지정은 바로 마감되지 않고, 요청자가 다른 사람에게 다시 부탁할 때 마감됩니다(TARGET_CHANGED).
     *   그래서 어려움으로 답했지만 아직 다른 사람에게 넘어가지 않은 대상은 true입니다. (대상이 바뀌기 전까지 수락으로 바꿀 수 있음, F-06)
     * - 완료된 요청은 지정을 마감하지 않아 마지막 담당자가 true로 남고, 취소·만료된 요청은 지정이 함께 마감돼 모두 false입니다.
     *   요청됨 외의 상태에서 쓸 때는 SharingRequestInfo.status와 함께 판단합니다.
     */
    boolean isCurrentTarget(Long requestId, Long userId);

    /**
     * 공유 판단용 요청 정보
     * - situationId: 급한 요청은 null
     * - startsAt, endsAt: 급한 요청은 null (일반 요청 접근 기한은 endsAt, 급한 요청은 D의 expires_at)
     * - assignmentId, assigneeId, acceptedAt: 현재 담당자(수락했고 마감되지 않은 지정). 없으면 셋 다 null
     *   완료된 요청은 지정을 마감하지 않으므로 완료 후에도 마지막 담당자 값이 담깁니다.
     *   취소·만료된 요청은 지정을 마감하므로 null입니다. (ERD 9.1)
     */
    record SharingRequestInfo(
            Long requestId, // 요청 ID
            Long requesterId, // 요청자 ID (정보 주인과 같은지 확인, ERD 8.3 참고)
            Long assignmentId, // 현재 담당자의 대상 지정 ID (권한의 assignment_id와 비교, ERD 8.3 참고)
            CareRequestStatus status, // 요청 상태
            RequestType requestType, // 요청 유형
            LocalDateTime startsAt, // 시작 시간
            LocalDateTime endsAt, // 종료 시간
            Long situationId, // 상황 ID
            Long assigneeId, // 현재 담당자 ID
            LocalDateTime acceptedAt, // 수락 시간
            HelpType helpType // 도움 종류
    ) {
    }
}