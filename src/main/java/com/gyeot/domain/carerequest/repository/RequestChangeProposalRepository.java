package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
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
     * 재확인 기한이 지난(response_deadline < now) 특정 상태 수정안의 요청 ID (#75 스케줄러, 기한 빠른 순)
     *
     * - 수정안이 아니라 요청 ID를 돌려줍니다. 스케줄러는 요청 행을 먼저 잠그고 수정안을 다시 읽어야 하기 때문입니다.
     *   수정안을 먼저 읽고 잠그면, 그사이 담당자가 재확인을 수락·거절해도 예전 상태(PENDING)로 보고 덮어쓸 수 있습니다.
     * - 기한 비교는 RequestChangeProposal.isDeadlinePassed(now.isAfter(deadline))와 같은 기준입니다.
     * - 잠그지 않는 조회입니다.
     */
    @Query("""
            select p.careRequest.id from RequestChangeProposal p
            where p.status = :status and p.responseDeadline < :now
            order by p.responseDeadline, p.id
            """)
    List<Long> findRequestIdsByStatusAndResponseDeadlineBefore(@Param("status") ProposalStatus status,
                                                               @Param("now") LocalDateTime now);
}
