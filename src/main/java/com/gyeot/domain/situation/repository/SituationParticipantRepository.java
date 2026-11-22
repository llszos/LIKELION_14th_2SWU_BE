package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.gyeot.domain.situation.dto.internal.SituationParticipantInfo;

public interface SituationParticipantRepository extends JpaRepository<SituationParticipant, Long> {
    /** 내부 값 projection. 호출자가 상태를 지정하며 정렬은 참여 시각·이력 PK 오름차순입니다. */
    @Query("""
            select new com.gyeot.domain.situation.dto.internal.SituationParticipantInfo(
                p.id, p.userId, p.role, p.status, p.joinedAt, p.leftAt)
            from SituationParticipant p
            where p.situation.id = :situationId and p.status = :status
            order by p.joinedAt asc, p.id asc
            """)
    List<SituationParticipantInfo> findInfoBySituationAndStatus(
            @Param("situationId") Long situationId, @Param("status") SituationParticipantStatus status);

    /** 상황 행을 먼저 잠근 트랜잭션에서 호출합니다. participantId는 참여 이력 PK입니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SituationParticipant p where p.situation.id = :situationId and p.id = :participantId")
    Optional<SituationParticipant> findBySituationAndParticipantForUpdate(
            @Param("situationId") Long situationId, @Param("participantId") Long participantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SituationParticipant p where p.situation.id = :situationId and p.userId = :userId order by p.id")
    List<SituationParticipant> findHistoryForUpdate(
            @Param("situationId") Long situationId, @Param("userId") Long userId);

    boolean existsByUserId(Long userId);

    @Query("""
            select p.situation.id as situationId, count(p) as participantCount
            from SituationParticipant p
            where p.situation.id in :situationIds and p.status = ACTIVE
            group by p.situation.id
            """)
    List<ActiveCount> countActiveBySituationIds(@Param("situationIds") Collection<Long> situationIds);

    interface ActiveCount {
        Long getSituationId();
        long getParticipantCount();
    }

    /** 역할·상황 상태는 제한하지 않으며 중복 참여 행의 사용자 ID도 보존합니다. */
    @Query("""
            select p.userId
            from SituationParticipant p
            join p.situation s
            where s.id = :situationId and p.status = :status
            order by p.joinedAt asc, p.id asc
            """)
    List<Long> findUserIdsBySituationAndStatus(
            @Param("situationId") Long situationId,
            @Param("status") SituationParticipantStatus status);

    /** 부모 상황이 실제 존재하는 참여 행만 확인하며, 상황 상태와 역할은 제한하지 않습니다. */
    @Query("""
            select (count(p) > 0)
            from SituationParticipant p
            join p.situation s
            where s.id = :situationId and p.userId = :userId and p.status = :status
            """)
    boolean existsBySituation_IdAndUserIdAndStatus(
            @Param("situationId") Long situationId,
            @Param("userId") Long userId,
            @Param("status") SituationParticipantStatus status);
}
