package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 돌봄 요청(care_requests)을 저장 및 조회합니다. (ERD 4.6 참조)
 * 상황별, 스케줄러용 조회 메서드는 해당 기능(#67, #75 등)을 만들 때 추가합니다.
 */
public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {
    /**
     * 요청 행을 잠그고 조회합니다.
     * 응답·수락 철회·취소·다시 부탁하기 등 요청 상태를 바꾸는 작업은 모두 이 메서드로 요청을 가져옵니다.
     * 같은 요청에 대한 작업이 동시에 들어오면 먼저 잠근 쪽이 끝날 때까지 다른 쪽은 기다립니다.
     * 기다린 쪽은 바뀐 상태를 보고 판단하므로 하나만 성공합니다. (중복 수락 방지 F-07)
     * 잠금은 트랜잭션이 끝날 때 풀리기 때문에 트랜잭션 안에서만 의미가 있습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CareRequest r where r.id = :id")
    Optional<CareRequest> findByIdForUpdate(@Param("id") Long id);

    // === #76 다른 영역 이벤트 수신 (상황 종료, 회원 탈퇴) ===
    // 처리할 요청 ID만 먼저 모읍니다. 실제 처리는 요청 행을 ID 순서로 하나씩 잠근 뒤 최신 값으로 다시 확인합니다.

    /** 상황의 특정 상태 요청 ID (요청 ID 순) */
    @Query("select r.id from CareRequest r where r.situationId = :situationId and r.status in :statuses order by r.id")
    List<Long> findIdsBySituationIdAndStatusIn(@Param("situationId") Long situationId,
                                               @Param("statuses") Collection<CareRequestStatus> statuses);

    /** 사용자가 보낸 특정 상태 요청 ID (요청 ID 순) */
    @Query("select r.id from CareRequest r where r.requesterId = :requesterId and r.status in :statuses order by r.id")
    List<Long> findIdsByRequesterIdAndStatusIn(@Param("requesterId") Long requesterId,
                                               @Param("statuses") Collection<CareRequestStatus> statuses);
}
