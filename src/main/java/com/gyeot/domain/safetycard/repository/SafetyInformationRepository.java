package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.entity.SafetyInformation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 안심카드 정보(safety_information)를 DB에 저장·조회·삭제합니다. (ERD 4.10 safety_information 안심카드 정보)
 * 저장(save), ID로 조회(findById), 삭제(delete) 같은 기본 기능은 JpaRepository가 이미 제공합니다.
 * 화면에 필요한 조회 메서드(예: 내 항목 목록)는 서비스·API를 만들 때 추가합니다.
 */

public interface SafetyInformationRepository extends JpaRepository<SafetyInformation, Long> {   // JpaRepository<다룰 엔티티, 그 엔티티의 ID 타입>
}