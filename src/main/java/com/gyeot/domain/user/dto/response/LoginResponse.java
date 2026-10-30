package com.gyeot.domain.user.dto.response;

/**
 * 로그인 성공 결과입니다.
 *
 * expiresIn은 Access Token의 유효기간이며 단위는 초입니다.
 * Refresh Token은 인증 2차에서 별도로 연결합니다.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    // 응답 객체를 로그에 출력해도 토큰 원문이 노출되지 않도록 합니다.
    @Override
    public String toString() {
        return "LoginResponse[accessToken=REDACTED, tokenType="
                + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
