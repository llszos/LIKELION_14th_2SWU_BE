package com.gyeot.domain.user.service;

import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 계정 탈퇴 상태 변경과 인증 세션 폐기를 한 트랜잭션에서 처리합니다. */
@Service
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public UserWithdrawalService(
            UserRepository userRepository,
            RefreshTokenService refreshTokenService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
        if (user.isWithdrawn()) {
            throw new AuthException(AuthErrorCode.USER_ALREADY_WITHDRAWN);
        }

        LocalDateTime occurredAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        user.withdraw(occurredAt);
        refreshTokenService.revokeAllForUser(userId);
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId, occurredAt));
    }
}
