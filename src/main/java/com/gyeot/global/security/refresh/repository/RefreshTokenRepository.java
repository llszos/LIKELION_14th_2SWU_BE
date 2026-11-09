package com.gyeot.global.security.refresh.repository;

import com.gyeot.global.security.refresh.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token where token.sessionId = :sessionId order by token.id")
    List<RefreshToken> findAllBySessionIdForUpdate(@Param("sessionId") String sessionId);

    List<RefreshToken> findAllBySessionId(String sessionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken token set token.usedAt = :now "
            + "where token.id = :id and token.usedAt is null and token.revokedAt is null and token.expiresAt > :now")
    int markUsedIfAvailable(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken token set token.revokedAt = :now "
            + "where token.sessionId = :sessionId and token.revokedAt is null")
    int revokeSession(@Param("sessionId") String sessionId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken token set token.revokedAt = :now "
            + "where token.userId = :userId and token.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    long deleteAllByExpiresAtBefore(LocalDateTime cutoff);
}
