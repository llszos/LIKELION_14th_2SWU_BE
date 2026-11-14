package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

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

    /**
     * 요청에 지금 공개 중인 항목 ID를 조회합니다. (F-03 요청 수정, 공개 항목 선택지 조회)
     * 삭제된 항목은 권한의 information_id가 NULL이라 빠집니다.
     */
    @Query("""
            select distinct p.informationId
            from RequestInformationPermission p
            where p.requestId = :requestId
              and p.status in :statuses
              and p.informationId is not null
            order by p.informationId
            """)
    List<Long> findInformationIdsByRequestIdAndStatusIn(@Param("requestId") Long requestId,
                                                        @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 요청의 마지막 대상 지정에 공개했던 항목 ID를 조회합니다. (공개 항목 선택지 조회의 requestId)
     * - 요청 수정(H-03-01-02): 마지막 지정이 지금 담당자라 공개 중(PENDING·ACTIVE)인 항목이 나옴
     * - 다시 부탁하기(N-06): 이전 대상이 거절·마감돼 권한이 끝났어도 그 요청에 공개했던 항목이 나옴
     * 정보 주인이 뺀 항목(excludedReasons: 공개 항목 제외, 직접 철회 등)과 삭제된 항목은 빠집니다.
     * 대상 지정 ID는 새로 만들수록 커지므로 가장 큰 ID를 마지막 지정으로 봅니다.
     */
    @Query("""
            select distinct p.informationId
            from RequestInformationPermission p
            where p.requestId = :requestId
              and p.assignmentId = (select max(p2.assignmentId)
                                    from RequestInformationPermission p2
                                    where p2.requestId = :requestId)
              and p.informationId is not null
              and (p.revokeReason is null or p.revokeReason not in :excludedReasons)
            order by p.informationId
            """)
    List<Long> findLastSharedInformationIds(@Param("requestId") Long requestId,
                                            @Param("excludedReasons") Collection<RevokeReason> excludedReasons);

    /**
     * 대상 지정 하나의 권한 중 지정한 상태인 것을 조회합니다. (#42 권한 부여와 회수)
     * 권한은 (지정, 항목) 단위라, 수락, 대상 변경, 수정 확정 때 그 지정의 PENDING, ACTIVE 권한을 찾는 데 씁니다.
     * 인덱스 idx_permissions_assignment_id_status를 사용합니다.
     */
    List<RequestInformationPermission> findAllByAssignmentIdAndStatusIn(Long assignmentId,
                                                                        Collection<PermissionStatus> statuses);

    /**
     * 요청 하나의 권한 중 지정한 상태인 것을 조회합니다. (#43 요청 종료 시 일괄 회수)
     *
     * 완료, 취소, 만료처럼 요청 자체가 끝나면 그 요청의 모든 지정(이전 대상 포함)의 PENDING, ACTIVE 권한을 찾는 데 씁니다.
     * 취소, 만료 이벤트는 열린 지정이 없으면 assignmentId가 NULL이라, 지정이 아닌 요청 기준으로 찾습니다.
     * 인덱스 idx_permissions_request_id_status를 사용합니다.
     */
    List<RequestInformationPermission> findAllByRequestIdAndStatusIn(Long requestId,
                                                                     Collection<PermissionStatus> statuses);

    /**
     * 기준 시각보다 이전(<)에 끝난 권한을 한 번에 삭제합니다. (보존 기간 2년 정리, #44)
     * 지금 쓰는 권한(PENDING·ACTIVE)과 정확히 기준 시각에 끝난 권한은 남깁니다.
     * 삭제 전에 이 권한을 가리키는 이력의 permission_id를 먼저 NULL로 바꿔야 합니다.
     * (InformationAccessLogRepository.clearPermissionIdOfRevokedBefore, 같은 조건)
     * 반환값은 삭제한 건수입니다.
     */
    @Modifying
    @Query("delete from RequestInformationPermission p where p.status = :status and p.revokedAt < :cutoff")
    int deleteAllRevokedBefore(@Param("status") PermissionStatus status,
                               @Param("cutoff") LocalDateTime cutoff);
}