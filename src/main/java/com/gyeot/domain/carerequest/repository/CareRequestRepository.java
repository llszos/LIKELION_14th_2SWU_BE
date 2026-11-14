package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    // === #75 스케줄러용 조회 ===
    // 잠그지 않고 ID만 읽습니다. 실제 처리는 건마다 요청 행을 잠근 뒤 상태와 시각을 다시 확인합니다.
    // (조회와 처리 사이에 사용자가 취소하거나 일정을 바꿀 수 있기 때문)

    /**
     * 시작 시각이 된(starts_at <= now) 특정 상태의 요청 ID (시작 시각 빠른 순)
     * - ACCEPTED: 자동 시작 대상 / REQUESTED: 담당자 없이 시작 시각이 되어 만료 대상
     * - 급한 요청은 starts_at이 NULL이라 조건에 걸리지 않습니다. (일반 요청만 자동 처리)
     * - 인덱스 idx_care_requests_status_starts_at
     */
    @Query("""
            select r.id from CareRequest r
            where r.status = :status and r.startsAt <= :now
            order by r.startsAt, r.id
            """)
    List<Long> findIdsByStatusAndStartsAtReached(@Param("status") CareRequestStatus status,
                                                 @Param("now") LocalDateTime now);

    /**
     * 종료 시각이 된(ends_at <= now) 특정 상태의 요청 ID (종료 시각 빠른 순)
     * - IN_PROGRESS: 자동 완료 대상
     * - 인덱스 idx_care_requests_status_ends_at
     */
    @Query("""
            select r.id from CareRequest r
            where r.status = :status and r.endsAt <= :now
            order by r.endsAt, r.id
            """)
    List<Long> findIdsByStatusAndEndsAtReached(@Param("status") CareRequestStatus status,
                                               @Param("now") LocalDateTime now);
}
