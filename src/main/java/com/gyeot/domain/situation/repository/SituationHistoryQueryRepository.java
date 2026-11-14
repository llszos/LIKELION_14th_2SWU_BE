package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 잠정 권한: 생성자 또는 ACTIVE 참여자. LEFT·REMOVED 허용 정책은 확정 전입니다. */
public interface SituationHistoryQueryRepository extends Repository<Situation, Long> {
    String ACCESS = """
            s.status = com.gyeot.domain.situation.entity.SituationStatus.ENDED
            and (s.creatorId = :userId or exists (
                select p.id from SituationParticipant p
                where p.situation = s and p.userId = :userId
                  and p.status = com.gyeot.domain.situation.entity.SituationParticipantStatus.ACTIVE
            ))
            """;

    @Query("select s from Situation s where " + ACCESS)
    Slice<Situation> findHistory(@Param("userId") Long userId, Pageable pageable);

    @Query("select s from Situation s where s.id = :situationId and " + ACCESS)
    Optional<Situation> findAccessibleHistory(@Param("situationId") Long situationId, @Param("userId") Long userId);
}
