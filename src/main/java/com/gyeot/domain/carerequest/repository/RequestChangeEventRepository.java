package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 요청 변경 이력(request_change_events)을 저장 및 조회합니다. (ERD 4.9 참조)
 * 이력은 한 번 쌓이면 바뀌지 않습니다.
 */
public interface RequestChangeEventRepository extends JpaRepository<RequestChangeEvent, Long> {
    /** 요청의 이력 전체 (발생 순) */
    List<RequestChangeEvent> findAllByCareRequestIdOrderByOccurredAtAscIdAsc(Long requestId);

}
