package com.gyeot.domain.access.query;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SharingCountReader(공유 건수 조회 인터페이스)의 실제 구현입니다.
 * ACTIVE 권한을 (요청, 담당자) 묶음 기준으로 셉니다. (F-30)
 * 사용하는 쪽(B의 홈 등)은 해당 구현 클래스가 아니라 SharingCountReader 타입으로 선언합니다.
 */

@Service                         // Spring이 이 클래스를 객체로 만들어 관리함. SharingCountReader를 주입받는 곳에 이 객체가 들어감
@RequiredArgsConstructor         // 아래 final 필드(permissionRepository)를 채우는 생성자 자동 생성. Spring이 이 생성자로 Repository를 넣어 줌
@Transactional(readOnly = true)  // 조회만 하므로 읽기 전용 트랜잭션으로 실행
public class SharingCountReaderImpl implements SharingCountReader {

    private final RequestInformationPermissionRepository permissionRepository; // 권한을 세는 쿼리가 있는 Repository

    @Override
    public long countActiveSharings(Long ownerUserId) {
        return permissionRepository.countSharings(ownerUserId, PermissionStatus.ACTIVE);
    }
}
