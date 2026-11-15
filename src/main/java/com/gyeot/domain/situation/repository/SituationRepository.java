package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface SituationRepository extends JpaRepository<Situation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Situation s where s.id = :id")
    Optional<Situation> findForUpdate(@Param("id") Long id);
}
