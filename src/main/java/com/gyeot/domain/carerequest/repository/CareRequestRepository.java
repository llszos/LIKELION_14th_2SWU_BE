package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;


/**
 * 돌봄 요청(care_requests)을 저장 및 조회합니다. (ERD 4.6 참조)
 * 상황별, 스케줄러용 조회 메서드는 다른 이슈(#67, #75 등)를 만들 때 추가할 예정입니다.
 */
public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {

    // === #67 요약 조회 (B 화면) ===

    /** 여러 상황의 요청 중 특정 상태인 것 (상황 카드 통계, 인덱스 situation_id, status) */
    List<CareRequest> findAllBySituationIdInAndStatusIn(Collection<Long> situationIds,
                                                        Collection<CareRequestStatus> statuses);

    /** 상황 하나의 요청 전체 (상황 상세 목록, 지난 상황 기록) */
    List<CareRequest> findAllBySituationId(Long situationId);

    /** 내가 보낸 요청 중 특정 상태인 것 (홈의 내가 보낸 요청·다가오는 일정) */
    List<CareRequest> findAllByRequesterIdAndStatusIn(Long requesterId, Collection<CareRequestStatus> statuses);

    /**
     * 여러 지난 상황의 요청 수·완료 수를 한 번에 셉니다. (H-04 지난 상황 카드)
     * 요청을 다 가져와 세지 않고 DB에서 묶어(group by) 세므로 상황 수와 상관없이 쿼리 1번입니다.
     * 요청이 없는 상황은 결과에 없습니다. (호출하는 쪽에서 0건으로 채움)
     */
    @Query("""
            select r.situationId as situationId,
                   count(r) as requestCount,
                   sum(case when r.status = :completed then 1 else 0 end) as completedCount
            from CareRequest r
            where r.situationId in :situationIds
            group by r.situationId
            """)
    List<SituationRequestCount> countBySituations(@Param("situationIds") Collection<Long> situationIds,
                                                  @Param("completed") CareRequestStatus completed);

    /** countBySituations의 결과 한 줄 (Spring Data가 위 쿼리의 별칭(as)으로 값을 채워 줌) */
    interface SituationRequestCount {
        Long getSituationId();

        long getRequestCount();

        long getCompletedCount();
    }

    /**
     * 도움 기록 (F-22, B #64). 끝난 요청 중 내가 받았거나(RECEIVED) 도와준(GIVEN) 요청을 페이지로 조회합니다.
     * - RECEIVED: 내가 요청자
     * - GIVEN: 내가 기록상 담당자 (요청이 끝날 때 담당자였던 사람, query.RecordAssigneeRule과 같은 규칙)
     *   - 시작한 요청: 마지막으로 수락한 지정
     *   - 시작 전에 끝난 요청: 열려 있거나 요청 종료(REQUEST_ENDED)로 함께 마감된 수락 지정
     * - personId: 상대방. RECEIVED면 기록상 담당자, GIVEN이면 요청자가 이 사람인 것만 (null이면 전체)
     * - 정렬: 시작 일시 최신순, 같으면 요청 ID 최신순 (급한 요청은 시작 일시가 없어 맨 뒤)
     *
     * exists(...): 조건에 맞는 지정이 하나라도 있는지만 확인합니다. 지정을 join하지 않아 요청이 중복되지 않고,
     * Spring Data가 같은 조건으로 전체 개수(count) 쿼리도 만들어 페이지 정보를 채워 줍니다.
     */
    @Query("""
            select r from CareRequest r
            where r.status in :endedStatuses
              and (
                (:includeReceived = true
                    and r.requesterId = :userId
                    and (:personId is null or exists (
                        select 1 from RequestAssignment a
                        where a.careRequest = r and a.targetUserId = :personId and a.acceptedAt is not null
                          and ((r.startedAt is not null and a.assignmentOrder = (
                                    select max(a2.assignmentOrder) from RequestAssignment a2
                                    where a2.careRequest = r and a2.acceptedAt is not null))
                               or (r.startedAt is null and (a.closedAt is null or a.closeReason = :requestEnded))))))
                or (:includeGiven = true
                    and exists (
                        select 1 from RequestAssignment a
                        where a.careRequest = r and a.targetUserId = :userId and a.acceptedAt is not null
                          and ((r.startedAt is not null and a.assignmentOrder = (
                                    select max(a2.assignmentOrder) from RequestAssignment a2
                                    where a2.careRequest = r and a2.acceptedAt is not null))
                               or (r.startedAt is null and (a.closedAt is null or a.closeReason = :requestEnded))))
                    and (:personId is null or r.requesterId = :personId))
              )
            order by r.startsAt desc, r.id desc
            """)
    Page<CareRequest> findHelpRecords(@Param("userId") Long userId,
                                      @Param("includeReceived") boolean includeReceived,
                                      @Param("includeGiven") boolean includeGiven,
                                      @Param("personId") Long personId,
                                      @Param("endedStatuses") Collection<CareRequestStatus> endedStatuses,
                                      @Param("requestEnded") AssignmentCloseReason requestEnded,
                                      Pageable pageable);
}
