package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 추가 비상연락 대상을 저장하고 조회합니다. */
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Long> {

    List<EmergencyContact> findAllByUserIdOrderByPriorityAsc(Long userId);
}
