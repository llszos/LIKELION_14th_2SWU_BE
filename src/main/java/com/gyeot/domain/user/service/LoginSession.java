package com.gyeot.domain.user.service;

import com.gyeot.domain.user.dto.response.LoginResponse;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;

public record LoginSession(LoginResponse response, IssuedRefreshToken refreshToken) {
}
