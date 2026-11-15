package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 요청 변경 제안(request_change_proposals)을 저장 및 조회합니다. (ERD 4.8 참조)
 */
public interface RequestChangeProposalRepository extends JpaRepository<RequestChangeProposal, Long> {

    /**
     * 요청의 특정 상태 수정안
     * PENDING으로 부르면 대기 중 수정안 (요청당 최대 1건)
     */
    Optional<RequestChangeProposal> findByCareRequestIdAndStatus(Long requestId, ProposalStatus status);

    /** 요청의 가장 최근 수정안 (상태 무관, 요청 상세의 latestProposal) */
    Optional<RequestChangeProposal> findFirstByCareRequestIdOrderByIdDesc(Long requestId);

    /**
     * 요청의 특정 상태 수정안을 잠금 읽기로 조회합니다. (요청 행을 잠근 뒤에만 사용, CareRequestLifecycle)
     * 다른 영역 트랜잭션 안에서도 마지막으로 커밋된 값을 읽기 위해 잠금 읽기로 가져옵니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RequestChangeProposal p where p.careRequest.id = :requestId and p.status = :status")
    Optional<RequestChangeProposal> findByRequestIdAndStatusForUpdate(@Param("requestId") Long requestId,
                                                                      @Param("status") ProposalStatus status);
}
