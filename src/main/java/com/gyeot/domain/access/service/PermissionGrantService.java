package com.gyeot.domain.access.service;

import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 요청 이벤트에 따라 안심카드 접근 권한을 만들고 활성화합니다. (F-03, F-09, ERD 9.2, #42)
 *
 * - 이벤트를 발행한 C 트랜잭션 안에서만 실행합니다. (MANDATORY)
 *   트랜잭션 없이 호출되면 C 변경과 권한 변경이 따로 저장될 수 있어 바로 실패시킵니다.
 * - 같은 요청의 C 이벤트는 요청 행 잠금(findByIdForUpdate)을 획득한 트랜잭션 안에서 동기로 발행되는 것을 전제로 합니다.
 *   그래서 권한이 아직 없는 상태에서도 같은 요청의 이벤트가 동시에 처리되지 않습니다.
 * - 정보 주인은 요청자입니다. (requesterId)
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionGrantService {

    private final PermissionWriter permissionWriter;

    /**
     * 요청 생성: 최초 지정에 공개 항목마다 PENDING 권한을 만듭니다.
     * 공개 항목이 0개인 요청은 권한을 만들지 않습니다.
     */
    public void grantOnRequestCreated(CareRequestCreatedEvent event) {
        permissionWriter.createPending(event.requestId(), event.assignmentId(),
                event.requesterId(), event.informationIds());
    }
}
