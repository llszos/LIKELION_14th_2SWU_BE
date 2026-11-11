package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
