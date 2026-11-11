package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 요청 대상자 지정(request_assignments)을 저장 및 조회합니다. (ERD 4.7 참조)
 * 메서드
 */
public interface RequestAssignmentRepository extends JpaRepository<RequestAssignment, Long> {

}
