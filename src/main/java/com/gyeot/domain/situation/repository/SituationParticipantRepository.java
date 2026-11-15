package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface SituationParticipantRepository extends JpaRepository<SituationParticipant, Long> {
    /** 상황 행을 먼저 잠근 트랜잭션에서 호출합니다. participantId는 참여 이력 PK입니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SituationParticipant p where p.situation.id = :situationId and p.id = :participantId")
    Optional<SituationParticipant> findBySituationAndParticipantForUpdate(
            @Param("situationId") Long situationId, @Param("participantId") Long participantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SituationParticipant p where p.situation.id = :situationId and p.userId = :userId order by p.id")
    List<SituationParticipant> findHistoryForUpdate(
            @Param("situationId") Long situationId, @Param("userId") Long userId);

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
