package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 안심카드 정보(safety_information)를 DB에 저장·조회·삭제합니다. (ERD 4.10 safety_information 안심카드 정보)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 화면에 필요한 조회 메서드(예: 내 항목 목록)는 서비스·API를 만들 때 추가합니다.
 */

public interface SafetyInformationRepository extends JpaRepository<SafetyInformation, Long> {   // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>

    // 내 항목 전체를 작성순(ID 오름차순)으로 조회 (공개 항목 선택지 조회)
    List<SafetyInformation> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    // 이미 같은 유형의 항목이 있는지 확인 (1개만 등록하는 항목의 중복 작성 거부)
    boolean existsByOwnerIdAndInformationType(Long ownerId, InformationType informationType);
}