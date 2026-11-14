package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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
     * 유형과 수정 시각만 읽고 암호화된 title, information_value 컬럼은 읽지 않아 복호화가 일어나지 않습니다.
     */
    @Query("""
            select new com.gyeot.domain.safetycard.repository.InformationTypeLastUpdated(s.informationType, max(s.updatedAt))
            from SafetyInformation s
            where s.ownerId = :ownerId
            group by s.informationType
            """)
    List<InformationTypeLastUpdated> findLastUpdatedByType(@Param("ownerId") Long ownerId);

    /**
     * 정보 주인의 항목 중 주어진 ID의 항목 ID와 유형만 조회합니다. (C 요청 상세의 항목 이름 표시)
     *
     * - 정보 주인 조건을 쿼리에 넣어, 다른 사람의 항목은 DB에서 꺼내지 않습니다.
     * - 없는 ID와 삭제된 항목은 행이 없으므로 결과에서 빠집니다.
     * - ID와 유형만 읽고 암호화된 title, information_value 컬럼은 읽지 않아 복호화가 일어나지 않습니다.
     */
    @Query("""
            select new com.gyeot.domain.safetycard.repository.InformationIdAndType(s.id, s.informationType)
            from SafetyInformation s
            where s.ownerId = :ownerId and s.id in :ids
            order by s.id
            """)
    List<InformationIdAndType> findIdAndTypeByOwnerIdAndIdIn(@Param("ownerId") Long ownerId, @Param("ids") Collection<Long> ids);
}