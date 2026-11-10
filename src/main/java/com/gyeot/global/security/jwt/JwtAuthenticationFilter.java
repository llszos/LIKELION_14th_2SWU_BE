package com.gyeot.global.security.jwt;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.ApiAuthenticationEntryPoint;
import com.gyeot.global.security.PublicApiRequestMatcher;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Access Token을 검증하고 로그인 사용자 정보를 설정합니다.
 *
 * 공개 경로는 잘못된 Authorization 헤더가 있어도 통과시키고,
 * 인증이 필요한 경로의 JWT 오류는 공통 401 처리기로 전달합니다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PublicApiRequestMatcher publicApiRequestMatcher;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            UserRepository userRepository,
            PublicApiRequestMatcher publicApiRequestMatcher,
            ApiAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.publicApiRequestMatcher = publicApiRequestMatcher;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (publicApiRequestMatcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!header.regionMatches(
                true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            rejectUnauthorized(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        Long userId;
        try {
            userId = tokenProvider.getUserId(token);
        } catch (JwtException | IllegalArgumentException exception) {
            rejectUnauthorized(request, response);
            return;
        }

        if (!userRepository.existsByIdAndAccountStatus(
                userId, AccountStatus.ACTIVE)) {
            rejectUnauthorized(request, response);
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
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException("Access Token이 유효하지 않습니다."));
    }
}
