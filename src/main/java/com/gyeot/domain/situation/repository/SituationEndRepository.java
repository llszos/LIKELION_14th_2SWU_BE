package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 종료 전용 저장소. 미병합 #89의 JpaRepository와 중복 정의하지 않습니다. */
public interface SituationEndRepository extends Repository<Situation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Situation s where s.id = :situationId")
    Optional<Situation> findByIdForUpdate(@Param("situationId") Long situationId);

    @Query("""
            select (count(p) > 0) from SituationParticipant p
            where p.situation.id = :situationId and p.userId = :userId
              and p.status = com.gyeot.domain.situation.entity.SituationParticipantStatus.ACTIVE
            """)
    boolean hasActiveParticipation(@Param("situationId") Long situationId, @Param("userId") Long userId);
}
