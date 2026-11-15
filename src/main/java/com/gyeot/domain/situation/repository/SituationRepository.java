package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SituationRepository extends JpaRepository<Situation, Long> {
    /** 참여 이력 수와 무관하게 상황별 한 행만 반환합니다. 정렬은 호출자의 Pageable로 고정합니다. */
    @Query("""
            select s from Situation s
            where s.status = com.gyeot.domain.situation.entity.SituationStatus.ACTIVE
              and exists (
                select p.id from SituationParticipant p
                where p.situation = s and p.userId = :userId
                  and p.status = com.gyeot.domain.situation.entity.SituationParticipantStatus.ACTIVE
              )
            """)
    Slice<Situation> findActiveForParticipant(@Param("userId") Long userId, Pageable pageable);
}
