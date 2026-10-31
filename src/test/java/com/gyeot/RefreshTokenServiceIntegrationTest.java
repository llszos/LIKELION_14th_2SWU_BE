package com.gyeot;

import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.entity.RefreshToken;
import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import com.gyeot.global.security.refresh.service.RefreshTokenCleanupScheduler;
import com.gyeot.global.security.refresh.service.RefreshTokenGenerator;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefreshTokenServiceIntegrationTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RefreshTokenGenerator tokenGenerator;
    @Autowired private RefreshTokenCleanupScheduler cleanupScheduler;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void rotatesTokenAndPreservesTheOriginalExpiry() {
        Long userId = createUser();
        IssuedRefreshToken first = refreshTokenService.issueInitial(userId);

        IssuedRefreshToken next = refreshTokenService.rotate(first.rawToken());

        RefreshToken used = findToken(first.rawToken());
        RefreshToken current = findToken(next.rawToken());
        assertNotNull(used.getUsedAt());
        assertEquals(first.sessionId(), next.sessionId());
        assertEquals(first.expiresAt(), next.expiresAt());
        assertEquals(first.expiresAt(), current.getExpiresAt());
    }

    @Test
    void classifiesMissingRevokedExpiredAndReusedTokens() {
        assertAuthError(() -> refreshTokenService.rotate("missing"), AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);

        Long userId = createUser();
        IssuedRefreshToken revoked = refreshTokenService.issueInitial(userId);
        refreshTokenService.revokeCurrentSession(revoked.rawToken());
        assertAuthError(() -> refreshTokenService.rotate(revoked.rawToken()), AuthErrorCode.REFRESH_TOKEN_REVOKED);

        String expiredRaw = tokenGenerator.generate();
        refreshTokenRepository.save(RefreshToken.create(userId, UUID.randomUUID().toString(),
                tokenGenerator.hash(expiredRaw), LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1)));
        assertAuthError(() -> refreshTokenService.rotate(expiredRaw), AuthErrorCode.REFRESH_TOKEN_EXPIRED);

        IssuedRefreshToken used = refreshTokenService.issueInitial(userId);
        refreshTokenService.rotate(used.rawToken());
        assertAuthError(() -> refreshTokenService.rotate(used.rawToken()), AuthErrorCode.REFRESH_TOKEN_ALREADY_USED);
        assertTrue(refreshTokenRepository.findAllBySessionId(used.sessionId()).stream()
                .allMatch(token -> token.getRevokedAt() != null));
    }

    @Test
    void twoConcurrentRefreshesCannotBothRotateAndReuseRevokesSession() throws Exception {
        Long userId = createUser();
        IssuedRefreshToken first = refreshTokenService.issueInitial(userId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = executor.submit(() -> rotateAfter(start, first.rawToken()));
            Future<Object> two = executor.submit(() -> rotateAfter(start, first.rawToken()));
            start.countDown();
            List<Object> results = List.of(one.get(), two.get());
            assertEquals(1, results.stream().filter(IssuedRefreshToken.class::isInstance).count());
            assertEquals(1, results.stream().filter(AuthException.class::isInstance).count());
            assertTrue(refreshTokenRepository.findAllBySessionId(first.sessionId()).stream()
                    .allMatch(token -> token.getRevokedAt() != null));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void userRevocationAndCleanupAreApplied() {
        Long userId = createUser();
        IssuedRefreshToken token = refreshTokenService.issueInitial(userId);
        refreshTokenService.revokeAllForUser(userId);
        assertTrue(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(token.rawToken()))
                .orElseThrow().getRevokedAt() != null);

        String oldRaw = tokenGenerator.generate();
        String recentRaw = tokenGenerator.generate();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        refreshTokenRepository.save(RefreshToken.create(userId, UUID.randomUUID().toString(),
                tokenGenerator.hash(oldRaw), now.minusDays(8)));
        refreshTokenRepository.save(RefreshToken.create(userId, UUID.randomUUID().toString(),
                tokenGenerator.hash(recentRaw), now.minusDays(2)));

        cleanupScheduler.deleteExpiredTokens();

        assertTrue(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(oldRaw)).isEmpty());
        assertTrue(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(recentRaw)).isPresent());
    }

    private Long createUser() {
        return userRepository.save(User.register("refresh-" + UUID.randomUUID() + "@example.com", "hash", "테스트")).getId();
    }

    private RefreshToken findToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)).orElseThrow();
    }

    private Object rotateAfter(CountDownLatch start, String rawToken) {
        try {
            start.await();
            return refreshTokenService.rotate(rawToken);
        } catch (AuthException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertAuthError(Runnable operation, AuthErrorCode expected) {
        AuthException exception = assertThrows(AuthException.class, operation::run);
        assertEquals(expected, exception.getErrorCode());
    }
}
