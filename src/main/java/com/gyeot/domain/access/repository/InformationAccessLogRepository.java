package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 정보 접근 이력(information_access_logs)을 DB에 저장, 조회, 삭제합니다.
 * (ERD 4.12 information_access_logs 정보 접근 이력)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 이력은 수정하지 않으므로 저장, 조회, 2년 지난 이력 삭제에만 씁니다.
 * F-25 접근 이력 조회에 필요한 메서드는 서비스를 만들 때 추가합니다.
 */

public interface InformationAccessLogRepository extends JpaRepository<InformationAccessLog, Long> { // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>

    /**
     * 기준 시각보다 이전(<)에 일어난 이력을 한 번에 삭제합니다. (보존 기간 2년 정리, #44)
     * 정확히 기준 시각인 이력은 남깁니다.
     * deleteBy... 메서드는 행을 하나씩 읽어 지우므로, 쿼리 한 번으로 지우도록 직접 작성했습니다.
     * 반환값은 삭제한 건수입니다.
     */
    @Modifying
    @Query("delete from InformationAccessLog l where l.occurredAt < :cutoff")
    int deleteAllOccurredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 곧 삭제할 권한(기준 시각보다 이전에 끝난 권한)을 가리키는 이력의 permission_id를 NULL로 바꿉니다. (#44)
     * ERD의 ON DELETE SET NULL과 같은 동작이며, FK를 만들지 않아 코드에서 처리합니다. (ERD 4.11)
     * 권한 삭제(RequestInformationPermissionRepository.deleteAllRevokedBefore)와 같은 조건을 써야 합니다.
     * 권한의 이력(부여·열람·회수)은 회수 시각 이전에 쌓이므로 보통은 이력 삭제 단계에서 함께 지워져 0건입니다.
     * 그래도 기준 시각 이후의 이력이 남아 있으면 삭제된 권한 ID를 가리키지 않도록 막는 단계입니다.
     * 반환값은 바꾼 건수입니다.
     */
    @Modifying
    @Query("""
            update InformationAccessLog l
            set l.permissionId = null
            where l.permissionId in (select p.id
                                     from RequestInformationPermission p
                                     where p.status = :status and p.revokedAt < :cutoff)
            """)
    int clearPermissionIdOfRevokedBefore(@Param("status") PermissionStatus status,
                                         @Param("cutoff") LocalDateTime cutoff);
}