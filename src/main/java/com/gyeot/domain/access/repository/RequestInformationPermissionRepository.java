package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 요청별 정보 접근 권한(request_information_permissions)을 DB에 저장, 조회, 삭제합니다.
 * (ERD 4.11 request_information_permissions 요청별 정보 접근 권한)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 회수, 공유 관리 목록 등에 필요한 조회 메서드는 서비스, API를 만들 때 추가합니다.
 */

public interface RequestInformationPermissionRepository extends JpaRepository<RequestInformationPermission, Long> { // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>

    /**
     * 정보 주인이 지금 공유 중인 건수를 셉니다. (F-30, 홈 F-34, 안심카드 메인 C-00)
     * 공유 1건 = (요청, 담당자) 묶음이고, 담당 지정 하나는 요청 하나와 담당자 한 명으로 정해지므로 담당 지정 ID 개수로 셉니다.
     * 권한에는 정보 주인 ID가 없어서, 공유한 안심카드 항목(safety_information)의 주인으로 찾습니다.
     */
    @Query("""
            select count(distinct p.assignmentId)
            from RequestInformationPermission p
            join SafetyInformation s on s.id = p.informationId
            where s.ownerId = :ownerUserId and p.status = :status
            """)

    long countSharings(@Param("ownerUserId") Long ownerUserId, @Param("status") PermissionStatus status);
}