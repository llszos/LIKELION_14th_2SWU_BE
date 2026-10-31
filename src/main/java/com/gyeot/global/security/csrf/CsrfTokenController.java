package com.gyeot.global.security.csrf;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/csrf")
public class CsrfTokenController {

    @GetMapping
    public CsrfTokenResponse getToken(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getToken());
    }

    public record CsrfTokenResponse(String token) { }
}
