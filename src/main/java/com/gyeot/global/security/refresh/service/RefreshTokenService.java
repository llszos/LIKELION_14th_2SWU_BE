package com.gyeot.global.security.refresh.service;

import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.entity.RefreshToken;
import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository tokenRepository;
    private final RefreshTokenGenerator tokenGenerator;
    private final RefreshTokenRotationTransaction rotationTransaction;
    private final RefreshTokenRevocationService revocationService;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokenRepository,
                               RefreshTokenGenerator tokenGenerator,
                               RefreshTokenRotationTransaction rotationTransaction,
                               RefreshTokenRevocationService revocationService,
                               Clock clock) {
        this.tokenRepository = tokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.rotationTransaction = rotationTransaction;
        this.revocationService = revocationService;
        this.clock = clock;
    }

    public IssuedRefreshToken issueInitial(Long userId) {
        return rotationTransaction.issueInitial(userId);
    }

    public IssuedRefreshToken rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        String tokenHash = tokenGenerator.hash(rawToken);
        RefreshToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (token.getRevokedAt() != null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (!token.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        if (token.getUsedAt() != null) {
            revocationService.revokeSessionByTokenHash(tokenHash);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_ALREADY_USED);
        }

        RefreshRotationResult result = rotationTransaction.rotate(tokenHash);
        if (result.reused()) {
            // 재사용 결과를 먼저 커밋하고 나서 세션 전체를 별도 트랜잭션으로 폐기합니다.
            revocationService.revokeSessionByTokenHash(tokenHash);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_ALREADY_USED);
        }
        return result.token();
    }

    public void revokeCurrentSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        revocationService.revokeSessionByTokenHash(tokenGenerator.hash(rawToken));
    }

    public void revokeAllForUser(Long userId) {
        revocationService.revokeAllForUser(userId);
    }
}
