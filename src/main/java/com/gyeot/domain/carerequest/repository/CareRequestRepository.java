package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;


import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 돌봄 요청(care_requests)을 저장 및 조회합니다. (ERD 4.6 참조)
 * 상황별, 스케줄러용 조회 메서드는 해당 기능(#67, #75 등)을 만들 때 추가합니다.
 */
public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {
    /**
     * 요청 행을 잠그고 조회합니다.
     * 응답·수락 철회·취소·다시 부탁하기 등 요청 상태를 바꾸는 작업은 모두 이 메서드로 요청을 가져옵니다.
     * 같은 요청에 대한 작업이 동시에 들어오면 먼저 잠근 쪽이 끝날 때까지 다른 쪽은 기다립니다.
     * 기다린 쪽은 바뀐 상태를 보고 판단하므로 하나만 성공합니다. (중복 수락 방지 F-07)
     * 잠금은 트랜잭션이 끝날 때 풀리기 때문에 트랜잭션 안에서만 의미가 있습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CareRequest r where r.id = :id")
    Optional<CareRequest> findByIdForUpdate(@Param("id") Long id);

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

    // === #75 스케줄러용 조회 ===
    // 잠그지 않고 ID만 읽습니다. 실제 처리는 건마다 요청 행을 잠근 뒤 상태와 시각을 다시 확인합니다.
    // (조회와 처리 사이에 사용자가 취소하거나 일정을 바꿀 수 있기 때문)

    /**
     * 시작 시각이 된(starts_at <= now) 특정 상태의 요청 ID (시작 시각 빠른 순)
     * - ACCEPTED: 자동 시작 대상 / REQUESTED: 담당자 없이 시작 시각이 되어 만료 대상
     * - 급한 요청은 starts_at이 NULL이라 조건에 걸리지 않습니다. (일반 요청만 자동 처리)
     * - 인덱스 idx_care_requests_status_starts_at
     */
    @Query("""
            select r.id from CareRequest r
            where r.status = :status and r.startsAt <= :now
            order by r.startsAt, r.id
            """)
    List<Long> findIdsByStatusAndStartsAtReached(@Param("status") CareRequestStatus status,
                                                 @Param("now") LocalDateTime now);

    /**
     * 종료 시각이 된(ends_at <= now) 특정 상태의 요청 ID (종료 시각 빠른 순)
     * - IN_PROGRESS: 자동 완료 대상
     * - 인덱스 idx_care_requests_status_ends_at
     */
    @Query("""
            select r.id from CareRequest r
            where r.status = :status and r.endsAt <= :now
            order by r.endsAt, r.id
            """)
    List<Long> findIdsByStatusAndEndsAtReached(@Param("status") CareRequestStatus status,
                                               @Param("now") LocalDateTime now);
}