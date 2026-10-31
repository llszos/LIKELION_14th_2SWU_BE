package com.gyeot.global.security.refresh.service;

import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenRepository tokenRepository;
    private final Clock clock;

    public RefreshTokenCleanupScheduler(RefreshTokenRepository tokenRepository, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void deleteExpiredTokens() {
        tokenRepository.deleteAllByExpiresAtBefore(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS).minusDays(7));
    }
}
