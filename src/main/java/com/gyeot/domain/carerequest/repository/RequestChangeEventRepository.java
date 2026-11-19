package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 요청 변경 이력(request_change_events)을 저장 및 조회합니다. (ERD 4.9 참조)
 * 이력은 한 번 쌓이면 바뀌지 않습니다.
 */
public interface RequestChangeEventRepository extends JpaRepository<RequestChangeEvent, Long> {
    /** 요청의 이력 전체 (발생 순) */
    List<RequestChangeEvent> findAllByCareRequestIdOrderByOccurredAtAscIdAsc(Long requestId);

    /**
     * 수정안의 특정 유형 이력 중 가장 최근 것 (요청 상세 latestProposal.invalidationReason)
     * 무효 사유는 수정안 테이블에 컬럼이 없고 RECONFIRMATION_INVALIDATED 이력의 event_details.reason에만 남습니다.
     */
    Optional<RequestChangeEvent> findFirstByProposalIdAndEventTypeOrderByIdDesc(Long proposalId,
                                                                                ChangeEventType eventType);

}