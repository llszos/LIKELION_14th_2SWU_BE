package com.gyeot.global.security.auth;

/** 컨트롤러에서 로그인한 사용자 ID를 일관되게 받기 위한 인증 정보입니다. */
public record AuthenticatedUser(Long userId) {
}
