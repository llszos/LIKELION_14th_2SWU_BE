package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.RequestType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 요청 대상자 지정(request_assignments)을 저장 및 조회합니다. (ERD 4.7 참조)
 */
public interface RequestAssignmentRepository extends JpaRepository<RequestAssignment, Long> {
    /**
     * 요청당 현재 열린 지정 (closed_at이 NULL인 지정)
     * 요청당 열린 지정은 최대 1건이라 Optional로 받습니다. 모두 마감됐으면 빈 Optional입니다.
     */
    Optional<RequestAssignment> findByCareRequestIdAndClosedAtIsNull(Long requestId);

    /**
     * 요청의 가장 최근 지정 (assignment_order가 가장 큰 지정, 마감 여부 무관)
     * 화면 상태(RequestDisplayStatus) 계산, 담당자 없음 사유 계산에 사용합니다.
     */
    Optional<RequestAssignment> findFirstByCareRequestIdOrderByAssignmentOrderDesc(Long requestId);

    /** 요청의 지정 이력 전체 (부탁 순서 1, 2, 3 ... 순) */
    List<RequestAssignment> findAllByCareRequestIdOrderByAssignmentOrderAsc(Long requestId);

    /**
     * 특정 사용자의 지정 (같은 요청에 같은 사용자는 한 번만 지정되므로 최대 1건)
     * 요청 관련자 확인(대상자, 담당자인지)에 사용합니다.
     */
    Optional<RequestAssignment> findByCareRequestIdAndTargetUserId(Long requestId, Long targetUserId);

    /**
     * 이 요청에 이 사용자가 지정된 적이 있는지를 (마감·미수락 포함) 확입
     * 행을 가져오지 않고 있는지만 확인합니다.
     */
    boolean existsByCareRequestIdAndTargetUserId(Long requestId, Long targetUserId);

    /**
     * 여러 요청의 현재 담당자 지정을 한 번에 조회합니다. (D 공유 관리 카드)
     * 현재 담당자 = 마감되지 않았고, 수락한 지정
     * 요청당 열린 지정은 최대 1건이라 요청당 결과도 최대 1건입니다.
     */
    List<RequestAssignment> findAllByCareRequestIdInAndClosedAtIsNullAndAcceptedAtIsNotNull(
            Collection<Long> requestIds);

    /**
     * 내가 보낸 요청 중 특정 상태인 요청의 지정 전체를 요청과 함께 가져옵니다. (#74 담당자 없음 안내 목록)
     * 요청마다 따로 조회하지 않도록 join fetch로 한 번에 가져옵니다. (N+1 방지)
     * 지정이 하나도 없는 요청은 담당자 없음 안내 대상이 아니라 결과에 없어도 됩니다.
     */
    @Query("select a from RequestAssignment a join fetch a.careRequest r "
            + "where r.requesterId = :requesterId and r.status = :status")
    List<RequestAssignment> findAllWithRequestByRequesterIdAndStatus(@Param("requesterId") Long requesterId,
                                                                     @Param("status") CareRequestStatus status);

    /**
     * 요청의 열린 지정을 잠금 읽기로 조회합니다. (요청 행을 잠근 뒤에만 사용, CareRequestLifecycle·#76)
     * 다른 영역 트랜잭션 안에서 처리할 때도 스냅샷이 아닌 마지막으로 커밋된 값을 읽기 위해 잠금 읽기로 가져옵니다.
     * 지정을 바꾸는 C 작업은 모두 요청 행을 먼저 잠그므로 요청 → 지정 순서로 잠가 순서가 어긋나지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RequestAssignment a where a.careRequest.id = :requestId and a.closedAt is null")
    Optional<RequestAssignment> findOpenByRequestIdForUpdate(@Param("requestId") Long requestId);


    // === #76 다른 영역 이벤트 수신 ===
    // 처리할 요청 ID만 먼저 모읍니다. (요청 ID 순, 실제 처리는 요청 행을 잠근 뒤 다시 확인)

    /** 상황 안에서 이 사용자가 지금 대상자·담당자인 요청 ID (참여 종료) */
    @Query("select a.careRequest.id from RequestAssignment a "
            + "where a.targetUserId = :userId and a.closedAt is null and a.careRequest.situationId = :situationId "
            + "order by a.careRequest.id")
    List<Long> findOpenRequestIdsInSituation(@Param("situationId") Long situationId, @Param("userId") Long userId);

    /** 이 사용자가 지금 대상자·담당자인 요청 ID (회원 탈퇴) */
    @Query("select a.careRequest.id from RequestAssignment a "
            + "where a.targetUserId = :userId and a.closedAt is null order by a.careRequest.id")
    List<Long> findOpenRequestIdsByTarget(@Param("userId") Long userId);

    /** 요청자가 이 사용자에게 보낸 특정 유형 요청 중 이 사용자가 지금 대상자·담당자인 요청 ID (관계 해제, 급한 요청) */
    @Query("select a.careRequest.id from RequestAssignment a "
            + "where a.targetUserId = :userId and a.closedAt is null "
            + "and a.careRequest.requesterId = :requesterId and a.careRequest.requestType = :requestType "
            + "order by a.careRequest.id")
    List<Long> findOpenRequestIdsByRequesterAndTarget(@Param("requesterId") Long requesterId,
                                                      @Param("userId") Long userId,
                                                      @Param("requestType") RequestType requestType);
}
