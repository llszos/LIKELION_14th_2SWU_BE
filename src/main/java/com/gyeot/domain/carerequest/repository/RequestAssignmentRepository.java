package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
