package com.gyeot.domain.relation.query;

import java.util.List;

/**
 * C 영역의 급한 요청에서 사용할 보호자·추가 비상연락 대상 순서 조회 계약입니다.
 * 관계 Entity와 사용자 표시 정보는 반환하지 않습니다.
 */
public interface EmergencyContactOrderReader {

    /**
     * 보호자를 guardianPriority 순으로, 추가 비상연락 대상을 사용자 지정 순서로 반환합니다.
     * 보호자가 없으면 추가 대상의 기존 순서가 그대로 반환됩니다.
     * 이 순서는 급한 요청의 연락에만 사용하며 일반 요청의 대상 매칭에는 사용하지 않습니다.
     * 사용자 계정 상태는 조회하지 않으므로, 탈퇴한 사용자도 활성 보호자 관계 또는 저장된 추가 비상연락 항목이
     * 남아 있으면 결과에 포함됩니다.
     *
     * @param userId 연락 순서를 조회할 사용자 ID
     * @return 1부터 시작하는 통합 순서. 결과 목록은 수정할 수 없습니다.
     */
    List<Contact> findOrderedContacts(Long userId);

    /** 다른 영역에 노출하는 비상연락 대상의 최소 정보입니다. */
    record Contact(Long userId, ContactType type, int priority) {
    }

    enum ContactType {
        GUARDIAN,
        MY_PERSON
    }
}
