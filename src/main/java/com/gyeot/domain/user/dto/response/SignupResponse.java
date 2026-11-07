package com.gyeot.domain.user.dto.response;

/**
 * 회원가입 결과입니다.
 * 회원가입 후 자동 로그인은 제공하지 않는 흐름을 기준으로 합니다.
 */
public record SignupResponse(
        Long userId
) {
}
