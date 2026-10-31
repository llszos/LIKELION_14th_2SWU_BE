package com.gyeot.global.security.jwt;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Access Token을 검증하고 로그인 사용자 정보를 설정합니다.
 *
 * 인증 오류의 공통 응답 형식 연동은 인증 2차에서 진행합니다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            UserRepository userRepository
    ) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!header.regionMatches(
                true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            rejectUnauthorized(response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        Long userId;
        try {
            userId = tokenProvider.getUserId(token);
        } catch (JwtException | IllegalArgumentException exception) {
            rejectUnauthorized(response);
            return;
        }

        if (!userRepository.existsByIdAndAccountStatus(
                userId, AccountStatus.ACTIVE)) {
            rejectUnauthorized(response);
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(userId);

        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        // 하위 처리의 예외는 토큰 검증 오류로 바꾸지 않습니다.
        filterChain.doFilter(request, response);
    }

    private void rejectUnauthorized(
            HttpServletResponse response
    ) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
