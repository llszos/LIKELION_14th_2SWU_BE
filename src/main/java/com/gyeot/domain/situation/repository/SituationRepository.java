package com.gyeot.domain.situation.repository;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SituationRepository extends JpaRepository<Situation, Long> {
}
