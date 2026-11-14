package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 돌봄 요청(care_requests)을 저장 및 조회합니다. (ERD 4.6 참조)
 * 상황별, 스케줄러용 조회 메서드는 다른 이슈(#67, #75 등)를 만들 때 추가할 예정입니다.
 */
public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {

}
