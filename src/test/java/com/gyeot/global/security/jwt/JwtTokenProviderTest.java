package com.gyeot.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private JwtProperties properties;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("gyeot-test");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofDays(30));
        provider = new JwtTokenProvider(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAndReadsAccessTokenWithFifteenMinuteExpiry() {
        String token = provider.createAccessToken(17L);

        Claims claims = provider.parseAccessToken(token);
        assertEquals(17L, provider.getUserId(token));
        assertEquals(Date.from(NOW.plus(Duration.ofMinutes(15))), claims.getExpiration());
        assertEquals("gyeot-test", claims.getIssuer());
    }

    @Test
    void rejectsExpiredToken() {
        JwtTokenProvider laterProvider = new JwtTokenProvider(properties,
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

        assertThrows(JwtException.class, () -> laterProvider.getUserId(provider.createAccessToken(17L)));
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.createAccessToken(17L);
        String[] parts = token.split("\\.");
        parts[1] = (parts[1].charAt(0) == 'A' ? "B" : "A") + parts[1].substring(1);
        String tampered = String.join(".", parts);

        assertThrows(JwtException.class, () -> provider.getUserId(tampered));
    }

    @Test
    void rejectsWrongIssuerAndMissingTokenType() {
        JwtProperties wrongIssuer = new JwtProperties();
        wrongIssuer.setSecret(SECRET);
        wrongIssuer.setIssuer("other-issuer");
        JwtTokenProvider otherProvider = new JwtTokenProvider(wrongIssuer, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(JwtException.class, () -> otherProvider.getUserId(provider.createAccessToken(17L)));

        String tokenWithoutType = Jwts.builder()
                .issuer("gyeot-test")
                .subject("17")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();
        assertThrows(JwtException.class, () -> provider.getUserId(tokenWithoutType));
    }

    @Test
    void rejectsMissingRequiredClaims() {
        assertThrows(JwtException.class,
                () -> provider.getUserId(
                        createSignedToken("17", true, false)));

        assertThrows(JwtException.class,
                () -> provider.getUserId(
                        createSignedToken("17", false, true)));

        assertThrows(JwtException.class,
                () -> provider.getUserId(
                        createSignedToken(null, true, true)));
    }

    @Test
    void rejectsInvalidUserId() {
        for (String subject : new String[]{"", "abc", "0", "-1"}) {
            assertThrows(JwtException.class,
                    () -> provider.getUserId(
                            createSignedToken(subject, true, true)));
        }
    }

    @Test
    void rejectsExpiryNotAfterIssuedAt() {
        String token = Jwts.builder()
                .issuer("gyeot-test")
                .subject("17")
                .claim("token_type", "access")
                .issuedAt(Date.from(NOW.plusSeconds(120)))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThrows(JwtException.class,
                () -> provider.getUserId(token));
    }

    private String createSignedToken(
            String subject,
            boolean includeIssuedAt,
            boolean includeExpiration
    ) {
        var builder = Jwts.builder()
                .issuer("gyeot-test")
                .claim("token_type", "access");

        if (subject != null) {
            builder.subject(subject);
        }

        if (includeIssuedAt) {
            builder.issuedAt(Date.from(NOW));
        }

        if (includeExpiration) {
            builder.expiration(Date.from(NOW.plusSeconds(900)));
        }

        return builder
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();
    }

}
