package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;
import java.time.LocalDate;

/** 종료 전용 저장소. 미병합 #89의 JpaRepository와 중복 정의하지 않습니다. */
public interface SituationEndRepository extends Repository<Situation, Long> {
    @Query("""
            select s.id from Situation s
            where s.status = com.gyeot.domain.situation.entity.SituationStatus.ACTIVE
              and s.endDate < :today
              and s.id > :afterId
            order by s.id
            """)
    List<Long> findExpiredIds(@Param("today") LocalDate today, @Param("afterId") Long afterId, Pageable pageable);

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
