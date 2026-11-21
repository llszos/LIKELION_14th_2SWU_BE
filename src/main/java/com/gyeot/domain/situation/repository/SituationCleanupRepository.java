package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** A 이벤트 정리 전용 조회. HTTP 관리 저장소와 분리하고 상황 → 참여 행 순서로 잠급니다. */
public interface SituationCleanupRepository extends Repository<Situation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Situation s where s.creatorId in :ownerIds order by s.id")
    List<Situation> lockOwnedSituations(@Param("ownerIds") Collection<Long> ownerIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Situation s where s.creatorId <> :userId and exists (
                select p.id from SituationParticipant p where p.situation = s and p.userId = :userId
            ) order by s.id
            """)
    List<Situation> lockParticipatedSituations(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SituationParticipant p where p.situation.id = :situationId order by p.id")
    List<SituationParticipant> lockParticipants(@Param("situationId") Long situationId);
}
