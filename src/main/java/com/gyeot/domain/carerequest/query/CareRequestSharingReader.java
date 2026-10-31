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