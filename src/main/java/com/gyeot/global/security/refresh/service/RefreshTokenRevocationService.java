package com.gyeot.global.security.refresh.service;

import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.refresh.entity.RefreshToken;
import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public RefreshTokenRevocationService(RefreshTokenRepository tokenRepository,
                                         UserRepository userRepository,
                                         Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeSessionByTokenHash(String tokenHash) {
        RefreshToken token = tokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (token == null) return;

        userRepository.findByIdForUpdate(token.getUserId());
        tokenRepository.findAllBySessionIdForUpdate(token.getSessionId());
        tokenRepository.revokeSession(token.getSessionId(), LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        userRepository.findByIdForUpdate(userId);
        tokenRepository.revokeAllForUser(userId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
