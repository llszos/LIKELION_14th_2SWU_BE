package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 안심카드 정보(safety_information)를 DB에 저장, 조회, 삭제합니다. (ERD 4.10 safety_information 안심카드 정보)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 화면에 필요한 조회 메서드(예: 내 항목 목록)는 서비스와 API를 만들 때 추가합니다.
 */

public interface SafetyInformationRepository extends JpaRepository<SafetyInformation, Long> {   // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>

    // 내 항목 전체를 작성순(ID 오름차순)으로 조회 (공개 항목 선택지 조회)
    List<SafetyInformation> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    // 이미 같은 유형의 항목이 있는지 확인 (1개만 등록하는 항목의 중복 작성 거부)
    boolean existsByOwnerIdAndInformationType(Long ownerId, InformationType informationType);

    // 한 분류에 속한 유형의 내 항목만 작성순으로 조회 (분류별 항목 조회)
    // 다른 분류의 항목은 DB에서 꺼내지 않으므로, 화면에 필요 없는 민감정보를 복호화하지 않음
    List<SafetyInformation> findAllByOwnerIdAndInformationTypeInOrderByIdAsc(Long ownerId, Collection<InformationType> informationTypes);

    /**
     * 내 항목의 유형별 가장 최근 수정 시각만 조회합니다. (안심카드 메인 조회)
     * 유형과 수정 시각만 읽고 암호화된 title과 information_value 컬럼은 읽지 않아 복호화가 일어나지 않습니다.
     */
    @Query("""
            select new com.gyeot.domain.safetycard.repository.InformationTypeLastUpdated(s.informationType, max(s.updatedAt))
            from SafetyInformation s
            where s.ownerId = :ownerId
            group by s.informationType
            """)
    List<InformationTypeLastUpdated> findLastUpdatedByType(@Param("ownerId") Long ownerId);

    /**
     * 항목 여러 개를 잠금 조회하고, 결과를 ID 오름차순으로 돌려줍니다. (#38 항목 삭제와 권한 변경의 직렬화)
     *
     * - 권한을 만들 때 항목의 존재와 소유를 확인하거나 거르는 데 씁니다. (PermissionWriter.loadOwnedItemTypes, filterOwnedItemIds)
     *   잠금 순서는 그 전에 PermissionWriter.lockItems가 findByIdForUpdate로 하나씩 잠가 정하고,
     *   여기서는 이미 확보한 항목을 최신 상태로 다시 확인합니다.
     * - DB에서는 잠금 읽기(FOR UPDATE)로 현재 상태를 확인합니다. 이미 삭제된 항목은 결과에 없습니다.
     *   이미 영속성 컨텍스트에 로딩한 항목의 상태가 자동으로 갱신되는 것은 아닙니다.
     * - 결과는 ID 오름차순입니다. 실제 잠금 획득 순서는 DB 실행 계획에 영향을 받으므로 ORDER BY만으로 보장하지 않습니다.
     *   엄격한 순차 획득이 필요하면 호출부에서 전체 대상 ID를 정렬해 findByIdForUpdate를 하나씩 호출합니다.
     * - 같은 트랜잭션에서 이미 획득한 행 잠금은 유지됩니다. 재조회가 엔티티 상태 갱신을 의미하지는 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SafetyInformation s where s.id in :ids order by s.id")
    List<SafetyInformation> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * 항목 하나를 잠금 조회합니다. (#38 항목 삭제와 권한 변경의 직렬화)
     *
     * - 항목 삭제: 삭제할 항목을 이 트랜잭션에서 처음 읽을 때 씁니다. 잠근 뒤 권한을 정리하므로,
     *   같은 항목으로 권한을 만들거나 바꾸는 작업은 삭제가 끝날 때까지 기다립니다.
     * - 권한 변경: PermissionWriter.lockItems가 정렬한 항목 ID마다 이 메서드를 차례로 불러, 잠금 획득 순서를 코드로 정합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SafetyInformation s where s.id = :id")
    Optional<SafetyInformation> findByIdForUpdate(@Param("id") Long id);
}
