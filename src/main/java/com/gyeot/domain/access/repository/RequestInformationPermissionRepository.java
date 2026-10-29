package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 요청별 정보 접근 권한(request_information_permissions)을 DB에 저장, 조회, 삭제합니다.
 * (ERD 4.11 request_information_permissions 요청별 정보 접근 권한)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 회수, 공유 관리 목록 등에 필요한 조회 메서드는 서비스, API를 만들 때 추가합니다.
 */

public interface RequestInformationPermissionRepository extends JpaRepository<RequestInformationPermission, Long> { // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>
}