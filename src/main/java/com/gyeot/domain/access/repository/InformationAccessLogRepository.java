package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.InformationAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정보 접근 이력(information_access_logs)을 DB에 저장, 조회, 삭제합니다.
 * (ERD 4.12 information_access_logs 정보 접근 이력)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 이력은 수정하지 않으므로 저장, 조회, 2년 지난 이력 삭제에만 씁니다.
 * F-25 접근 이력 조회, 보관 기간 삭제에 필요한 메서드는 서비스, 스케줄러를 만들 때 추가합니다.
 */

public interface InformationAccessLogRepository extends JpaRepository<InformationAccessLog, Long> { // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>
}