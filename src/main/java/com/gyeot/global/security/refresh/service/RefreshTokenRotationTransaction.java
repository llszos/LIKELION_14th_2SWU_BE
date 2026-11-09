package com.gyeot.global.security.refresh.service;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.jwt.JwtProperties;
import com.gyeot.global.security.refresh.entity.RefreshToken;
import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class RefreshTokenRotationTransaction {

    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenGenerator tokenGenerator;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public RefreshTokenRotationTransaction(RefreshTokenRepository tokenRepository,
                                           UserRepository userRepository,
                                           RefreshTokenGenerator tokenGenerator,
                                           JwtProperties jwtProperties,
                                           Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issueInitial(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .filter(account -> account.getAccountStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_ACTIVE));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        String rawToken = tokenGenerator.generate();
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = now.plus(jwtProperties.getRefreshTokenTtl());
        tokenRepository.save(RefreshToken.create(user.getId(), sessionId, tokenGenerator.hash(rawToken), expiresAt));
        return new IssuedRefreshToken(user.getId(), rawToken, sessionId, expiresAt);
    }

    @Transactional
    public RefreshRotationResult rotate(String tokenHash) {
        RefreshToken initial = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

        User user = userRepository.findByIdForUpdate(initial.getUserId())
                .filter(account -> account.getAccountStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_ACTIVE));

        tokenRepository.findAllBySessionIdForUpdate(initial.getSessionId());
        RefreshToken current = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        if (current.getRevokedAt() != null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        if (current.getUsedAt() != null) {
            return RefreshRotationResult.detectedReuse();
        }

        if (tokenRepository.markUsedIfAvailable(current.getId(), now) != 1) {
            return RefreshRotationResult.detectedReuse();
        }

        String nextRawToken = tokenGenerator.generate();
        tokenRepository.save(RefreshToken.create(
                user.getId(), current.getSessionId(), tokenGenerator.hash(nextRawToken), current.getExpiresAt()));
        return RefreshRotationResult.rotated(new IssuedRefreshToken(
                user.getId(), nextRawToken, current.getSessionId(), current.getExpiresAt()));
    }
}
