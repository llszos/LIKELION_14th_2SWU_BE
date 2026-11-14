package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * - 요청 수정(H-03-01-02): 마지막 지정이 지금 담당자라 공개 중(PENDING, ACTIVE)인 항목이 나옴
     * - 다시 부탁하기(N-06): 이전 대상이 거절되거나 마감돼 권한이 끝났어도 그 요청에 공개했던 항목이 나옴
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
     * 대상 지정 하나의 열린 권한이 가리키는 항목 ID만 조회합니다. (#38 잠금 순서, 잠그기 전 단계)
     *
     * - 권한 엔티티를 읽지 않고 ID 값만 읽습니다. 잠그기 전에 엔티티를 읽으면 영속성 컨텍스트에 예전 상태가 남아,
     *   잠근 뒤 다시 조회해도 그 객체가 갱신되지 않기 때문입니다.
     * - 잠글 항목을 정하는 데 씁니다. 잠금 없는 조회라 최신이 아닐 수 있지만,
     *   권한 상태는 항목을 잠근 뒤 잠금 조회로 다시 확보하므로 판단에 쓰지 않습니다.
     * - 권한을 만들기 전 중복 확인에도 씁니다. (PermissionWriter.openInformationIds, #38 리뷰 반영)
     *   범위 잠금 조회는 빈 범위에 갭 락을 걸어 뒤이은 INSERT와 교착 위험이 있어 이 조회로 바꿨습니다.
     */
    @Query("""
            select distinct p.informationId
            from RequestInformationPermission p
            where p.assignmentId = :assignmentId
              and p.status in :statuses
              and p.informationId is not null
            """)
    List<Long> findInformationIdsByAssignmentIdAndStatusIn(@Param("assignmentId") Long assignmentId,
                                                           @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 대상 지정 하나의 권한 중 지정한 상태인 것을 잠금 조회합니다. (#42 권한 부여와 회수, #38 잠금 적용)
     *
     * - 권한은 (지정, 항목) 단위라, 수락, 대상 변경, 수정 확정 때 그 지정의 PENDING, ACTIVE 권한을 찾는 데 씁니다.
     * - DB에서는 잠금 읽기(FOR UPDATE)로 현재 상태를 확인하고, 획득한 행 잠금을 트랜잭션 종료까지 유지합니다.
     *   일반 조회는 REPEATABLE READ에서 이전 스냅샷을 볼 수 있습니다.
     * - 권한 엔티티는 이 잠금 조회 전에 로딩하지 않습니다. 이미 로딩한 엔티티의 상태를 자동 갱신하는 용도가 아닙니다.
     * - 결과는 ID 오름차순입니다. 실제 잠금 획득 순서는 DB 실행 계획에 영향을 받으므로 ORDER BY만으로 보장하지 않습니다.
     * - 인덱스 idx_permissions_assignment_id_status를 사용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from RequestInformationPermission p
            where p.assignmentId = :assignmentId
              and p.status in :statuses
            order by p.id
            """)
    List<RequestInformationPermission> findAllByAssignmentIdAndStatusIn(@Param("assignmentId") Long assignmentId,
                                                                        @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 대상 지정 하나의 권한 중 지정한 상태인 것의 권한 ID만 오름차순으로 조회합니다. (#38 리뷰 반영, 기본 키 잠금 전 단계)
     *
     * - 잠그지 않는 조회입니다. 권한이 없는 지정을 범위로 잠그면 빈 인덱스 범위에 갭 락이 걸리고,
     *   같은 트랜잭션이 그 뒤 권한을 INSERT하면 다른 요청의 INSERT와 서로 기다려 교착이 날 수 있어 범위 잠금을 피합니다.
     * - 이 ID 목록으로 findByIdForUpdate를 하나씩 부릅니다. 존재하는 권한을 기본 키로 조회하면 해당 행만 잠가 범위 조회의 갭 락을 줄입니다.
     *   조회 전에 행이 삭제된 경우에는 갭 락이 생길 수 있습니다.
     * - 잠그지 않는 조회라 REPEATABLE READ에서 이전 스냅샷을 볼 수 있습니다. 그래서 대상 변경과 수정 확정처럼
     *   C 요청 행 잠금 안에서만 이 지정에 권한이 추가되는 경로에서만 씁니다. (항목 삭제, 회수 경로는 범위 잠금 유지)
     *   호출 측은 요청 행 잠금 전에 일반 조회로 스냅샷을 만들지 않아야 합니다. 오래된 스냅샷에서 빠진 ID는
     *   이후 기본 키 잠금 조회로도 찾을 수 없습니다. (PermissionWriter.lockOpenPermissionsById의 호출 전제)
     * - 엔티티가 아니라 ID만 읽어, 잠그기 전에 예전 상태가 영속성 컨텍스트에 남지 않게 합니다.
     */
    @Query("""
            select p.id
            from RequestInformationPermission p
            where p.assignmentId = :assignmentId
              and p.status in :statuses
            order by p.id
            """)
    List<Long> findIdsByAssignmentIdAndStatusIn(@Param("assignmentId") Long assignmentId,
                                                @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 권한 하나를 기본 키로 잠금 조회합니다. (#38 리뷰 반영)
     *
     * - 존재하는 권한을 기본 키로 조회하면 해당 행만 잠가 범위 조회의 갭 락을 줄입니다.
     *   조회 전에 행이 삭제된 경우에는 갭 락이 생길 수 있습니다. (예: 보존 기간이 지난 권한 삭제, #44)
     * - 잠금 조회라 최신 커밋 상태를 읽습니다. ID를 모은 뒤 다른 트랜잭션(항목 삭제 등)이 회수했다면 REVOKED로 보입니다.
     * - 여러 개를 잠글 때는 호출하는 쪽이 ID 오름차순으로 하나씩 부릅니다. IN 조회 한 번으로는 잠금 순서를 보장할 수 없습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RequestInformationPermission p where p.id = :id")
    Optional<RequestInformationPermission> findByIdForUpdate(@Param("id") Long id);

    /**
     * 요청 하나의 권한 중 지정한 상태인 것을 잠금 조회합니다. (#43 요청 종료 시 일괄 회수, #38 잠금 적용)
     *
     * - 완료, 취소, 만료처럼 요청 자체가 끝나면 그 요청의 모든 지정(이전 대상 포함)의 PENDING, ACTIVE 권한을 찾는 데 씁니다.
     * - 취소, 만료 이벤트는 열린 지정이 없으면 assignmentId가 NULL이라, 지정이 아닌 요청 기준으로 찾습니다.
     * - 선행 엔티티 로딩을 피해야 한다는 조건과 결과 정렬, 잠금 순서의 한계는 findAllByAssignmentIdAndStatusIn과 같습니다.
     * - 인덱스 idx_permissions_request_id_status를 사용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from RequestInformationPermission p
            where p.requestId = :requestId
              and p.status in :statuses
            order by p.id
            """)
    List<RequestInformationPermission> findAllByRequestIdAndStatusIn(@Param("requestId") Long requestId,
                                                                     @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 안심카드 항목 하나의 권한 중 지정한 상태인 것을 잠금 조회합니다. (#38 항목 삭제 시 회수)
     *
     * - 한 항목은 여러 요청에 공개될 수 있어, 삭제할 때 그 항목이 걸린 모든 요청의 PENDING, ACTIVE 권한을 찾는 데 씁니다.
     * - 선행 엔티티 로딩을 피해야 한다는 조건과 결과 정렬, 잠금 순서의 한계는 findAllByAssignmentIdAndStatusIn과 같습니다.
     * - 인덱스 idx_permissions_information_id_status를 사용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from RequestInformationPermission p
            where p.informationId = :informationId
              and p.status in :statuses
            order by p.id
            """)
    List<RequestInformationPermission> findAllByInformationIdAndStatusIn(@Param("informationId") Long informationId,
                                                                         @Param("statuses") Collection<PermissionStatus> statuses);

    /**
     * 삭제되는 안심카드 항목을 가리키던 모든 권한의 information_id를 NULL로 바꿉니다. (#38, ERD 9.5 삭제 순서 4)
     *
     * - 열린 권한만이 아니라 이미 회수된 과거 권한까지 모두 바꿉니다. 원본이 없는 ID가 남지 않게 하기 위해서입니다.
     * - information_type과 접근 이력은 그대로 두어, 무엇을 공유했는지는 계속 알 수 있습니다.
     * - FK가 없어 DB가 대신 처리해 주지 않으므로 코드에서 바꿉니다.
     * - flushAutomatically: 같은 트랜잭션에서 방금 회수한 권한(상태 변경)을 먼저 DB에 반영한 뒤 실행합니다.
     *
     * 반환값은 바뀐 행 수입니다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RequestInformationPermission p set p.informationId = null where p.informationId = :informationId")
    int clearInformationId(@Param("informationId") Long informationId);

    /**
     * 기준 시각보다 이전(<)에 끝난 권한을 한 번에 삭제합니다. (보존 기간 2년 정리, #44)
     * 지금 쓰는 권한(PENDING, ACTIVE)과 정확히 기준 시각에 끝난 권한은 남깁니다.
     * 삭제 전에 이 권한을 가리키는 이력의 permission_id를 먼저 NULL로 바꿔야 합니다.
     * (InformationAccessLogRepository.clearPermissionIdOfRevokedBefore, 같은 조건)
     * 반환값은 삭제한 건수입니다.
     */
    @Modifying
    @Query("delete from RequestInformationPermission p where p.status = :status and p.revokedAt < :cutoff")
    int deleteAllRevokedBefore(@Param("status") PermissionStatus status,
                               @Param("cutoff") LocalDateTime cutoff);
}
