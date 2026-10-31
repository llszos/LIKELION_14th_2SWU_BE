# 인증 연동

## 로그인 사용자 ID

인증이 필요한 컨트롤러는 `@AuthenticationPrincipal`로 `AuthenticatedUser`를 받습니다. 이 클래스는 record이므로 `getUserId()`가 아니라 `userId()`를 호출합니다.

```java
@GetMapping("/me")
public Long me(@AuthenticationPrincipal AuthenticatedUser user) {
    return user.userId();
}
```

## 프론트엔드 토큰 처리

- 로그인 응답 본문에는 Access Token만 포함됩니다. 프론트엔드는 메모리에 보관하고 `Authorization: Bearer {token}`으로 전송합니다. Swagger UI의 **Authorize** 버튼에도 Access Token을 입력할 수 있습니다.
- Refresh Token은 HttpOnly 쿠키로 설정됩니다. 프론트 코드에서 읽거나 저장하지 않습니다.
- 로그인 전 `GET /api/auth/csrf`를 호출해 받은 CSRF 토큰을 unsafe 요청의 `X-XSRF-TOKEN` 헤더에 담습니다.
- 로그인·재발급·로그아웃 요청에는 브라우저의 쿠키 전송 설정(`credentials: "include"`)을 사용합니다.
- Access Token 만료 시 `POST /api/auth/refresh`를 호출합니다. 동시에 여러 요청이 만료되면 재발급 요청은 하나로 합칩니다.
- 로그아웃은 `POST /api/auth/logout`으로 현재 Refresh Token 세션을 폐기하고 쿠키를 지웁니다. 기존 Access Token은 최대 15분 더 유효할 수 있으므로 프론트에서도 즉시 삭제합니다.

## 환경 설정

`JWT_SECRET`에는 Base64로 인코딩한 32바이트 이상의 비밀 키를 전달합니다. 프론트 주소가 확정되면 `CORS_ALLOWED_ORIGINS`를 해당 주소로 설정하고, HTTPS 운영 환경에서는 `COOKIE_SECURE=true` 및 배포 구조에 맞는 `COOKIE_SAME_SITE`·`COOKIE_DOMAIN`을 지정합니다.
