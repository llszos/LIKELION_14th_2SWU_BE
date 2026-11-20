package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface SituationRepository extends JpaRepository<Situation, Long> {
    @Query("""
            select s.id as situationId, s.creatorId as creatorId, s.title as name,
                   s.startAt as startAt, s.endAt as endAt, count(p) as participantCount
            from Situation s
            join SituationParticipant p on p.situation = s and p.status = ACTIVE
            where s.status = ACTIVE and exists (
                select 1 from SituationParticipant viewer
                where viewer.situation = s and viewer.userId = :userId and viewer.status = ACTIVE)
            group by s.id, s.creatorId, s.title, s.startAt, s.endAt, s.createdAt
            order by s.createdAt desc, s.id desc
            """)
    List<HomeCardRow> findHomeCards(Long userId);

    boolean existsByCreatorId(Long creatorId);

    interface HomeCardRow {
        Long getSituationId();
        Long getCreatorId();
        String getName();
        LocalDateTime getStartAt();
        LocalDateTime getEndAt();
        long getParticipantCount();
    }
}
