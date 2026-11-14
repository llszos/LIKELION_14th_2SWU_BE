package com.gyeot.domain.user.repository;

import com.gyeot.domain.user.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
}
