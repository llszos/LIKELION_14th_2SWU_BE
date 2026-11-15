package com.gyeot.domain.safetycard.query;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 다른 영역(C 요청)이 안심카드 공개 항목을 확인할 때 쓰는 조회 인터페이스입니다. (D → C, F-03)
 * 요청 생성, 수정 때 FE가 고른 항목 ID 목록을 검증하고, 요청 수정 때 지금 공개 중인 항목과 항목 이름을 알려 줍니다.
 * 안심카드 Entity는 돌려주지 않고 항목 ID와 유형 이름만 주고받습니다. (항목 내용과 사용자가 적은 title은 넘기지 않음)
 * 사용하는 쪽은 필드 타입을 구현 클래스가 아니라 이 인터페이스로 선언합니다. (실제 구현은 SafetyInformationSharingReaderImpl)
 */
public interface SafetyInformationSharingReader {

    /**
     * 요청에 공개하려는 항목 ID 목록이 모두 요청자 본인의 항목인지 확인합니다. (ERD 4.8 proposed_information_ids)
     * C는 요청을 저장하기 전에 호출하고, 예외는 잡지 않고 그대로 둡니다. (C 요청 API가 400을 그대로 반환)
     * - 빈 목록이나 null은 통과합니다. (공개 항목 0개 요청 허용)
     * - 없는 ID, 다른 사람의 ID, 직전에 삭제된 ID, null, 중복 ID가 하나라도 있으면 요청 전체를 거부합니다.
     *
     * @param ownerUserId    정보 주인 ID (요청자 ID)
     * @param informationIds FE가 고른 안심카드 항목 ID 목록
     * @throws com.gyeot.global.exception.BusinessException SAFETY_INFO_INVALID_SELECTION (400)
     */
    void validateSelection(Long ownerUserId, List<Long> informationIds);

    /**
     * 요청에 지금 공개 중인 항목 ID 목록을 돌려줍니다. (요청 수정 H-03-01-02에서 변경 전 목록 확인용)
     * 수락 전(PENDING)과 수락 후(ACTIVE) 권한을 모두 포함하고, 끝난 권한(REVOKED)과 삭제된 항목은 빠집니다.
     *
     * @param requestId 요청 ID
     * @return 공개 중인 항목 ID 목록 (ID 오름차순, 중복 없음). 없으면 빈 목록
     */
    List<Long> findSharingInformationIds(Long requestId);

    /**
     * 항목 ID별 유형 이름을 돌려줍니다. (C 요청 상세 N-05 변경 내용 확인에서 변경 전후 공개 항목을 이름으로 표시)
     *
     * - 이름은 화면에 보이는 유형 이름(API의 typeName, 예: "복용약")입니다. 사용자가 적은 title은 아닙니다.
     * - 요청자 본인의 항목만 돌려줍니다. 다른 사람의 항목, 없는 ID, 삭제된 항목은 예외 없이 결과에서 빠집니다.
     * - null이나 빈 목록이면 조회하지 않고 빈 Map을 돌려줍니다. 목록 안의 null은 무시하고, 중복 ID는 한 번만 담습니다.
     * - Map 순서는 보장하지 않습니다. C는 가진 ID 목록 순서대로 이름을 꺼내 씁니다.
     * - 서로 다른 항목이 같은 이름을 가질 수 있습니다. (예: 복용약 2개)
     *
     * @param ownerUserId    정보 주인 ID (요청자 ID)
     * @param informationIds 이름을 알고 싶은 안심카드 항목 ID 목록
     * @return 항목 ID → 유형 이름 (수정 불가). 돌려줄 항목이 없으면 빈 Map
     */
    Map<Long, String> findInformationNames(Long ownerUserId, Collection<Long> informationIds);
}
