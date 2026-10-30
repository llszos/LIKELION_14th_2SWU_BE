package com.gyeot.domain.access.service;

/**
 * 다른 영역이 D에게 공유 건수를 물어볼 때 쓰는 조회 인터페이스입니다.
 * 홈(F-34)과 안심카드 메인(C-00)의 "지금 공유 중 n건"에 사용합니다.
 * 공유 1건은 (요청, 담당자) 묶음이고, C-06 공유 관리의 카드 하나와 같습니다. (F-30)
 * 사용하는 쪽은 필드 타입을 구현 클래스가 아니라 해당 인터페이스로 선언합니다. (실제로 세는 코드는 SharingCountReaderImpl)
 */

public interface SharingCountReader {

    /**
     * 정보 주인이 지금 공유 중인 건수를 돌려줍니다.
     * 담당자가 수락해 정보를 볼 수 있는(ACTIVE) 공유만 셉니다. 수락 전 공유와 끝난 공유는 세지 않습니다.
     *
     * @param ownerUserId 정보 주인 ID
     * @return 공유 중인 (요청, 담당자) 묶음 수. 없으면 0
     */
    long countActiveSharings(Long ownerUserId);
}