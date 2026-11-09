package com.gyeot.global.security.refresh.service;

import java.time.LocalDateTime;

public record IssuedRefreshToken(Long userId, String rawToken, String sessionId, LocalDateTime expiresAt) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[userId=" + userId + ", rawToken=REDACTED, sessionId="
                + sessionId + ", expiresAt=" + expiresAt + "]";
    }
}
