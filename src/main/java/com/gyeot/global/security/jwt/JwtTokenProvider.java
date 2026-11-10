package com.gyeot.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET 환경 변수가 필요합니다.");
        }
        try {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.getSecret()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JWT_SECRET은 Base64 형식의 256비트 이상 키여야 합니다.", exception);
        }
        if (properties.getIssuer() == null || properties.getIssuer().isBlank()) {
            throw new IllegalStateException("JWT 발급자 설정이 필요합니다.");
        }
    }

    public String createAccessToken(Long userId, Instant issuedAt) {
        if (userId == null || userId <= 0 || issuedAt == null) {
            throw new IllegalArgumentException("사용자 ID와 발급 시각은 필수입니다.");
        }
        Duration ttl = properties.getAccessTokenTtl();
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(userId.toString())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(signingKey)
                .compact();
    }

    public String createAccessToken(Long userId) {
        return createAccessToken(userId, clock.instant());
    }

    public Long getUserId(String token) {
        Claims claims = parseAccessToken(token);
        try {
            long userId = Long.parseLong(claims.getSubject());
            if (userId <= 0) {
                throw new IllegalArgumentException("JWT 사용자 ID가 올바르지 않습니다.");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JWT 사용자 ID가 올바르지 않습니다.", exception);
        }
    }

    public Claims parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Access Token이 필요합니다.");
        }

        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getIssuer())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!ACCESS_TOKEN_TYPE.equals(
                claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new JwtException("Access Token이 아닙니다.");
        }

        if (claims.getExpiration() == null) {
            throw new JwtException("JWT 만료 시각이 필요합니다.");
        }

        if (claims.getIssuedAt() == null) {
            throw new JwtException("JWT 발급 시각이 필요합니다.");
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtException("JWT 사용자 ID가 필요합니다.");
        }

        try {
            if (Long.parseLong(subject) <= 0) {
                throw new JwtException("JWT 사용자 ID가 올바르지 않습니다.");
            }
        } catch (NumberFormatException exception) {
            throw new JwtException(
                    "JWT 사용자 ID가 올바르지 않습니다.", exception);
        }

        if (!claims.getExpiration().after(claims.getIssuedAt())) {
            throw new JwtException("JWT 만료 시각은 발급 시각 이후여야 합니다.");
        }

        return claims;
    }

}
